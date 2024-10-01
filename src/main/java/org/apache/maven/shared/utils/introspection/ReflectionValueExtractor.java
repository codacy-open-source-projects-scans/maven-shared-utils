/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.shared.utils.introspection;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.shared.utils.introspection.MethodMap.AmbiguousException;

/**
 * <p>Using simple dotted expressions to extract the values from an Object instance,
 * For example we might want to extract a value like: <code>project.build.sourceDirectory</code></p>
 * <p>The implementation supports indexed, nested and mapped properties similar to the JSP way.</p>
 *
 * @author <a href="mailto:jason@maven.org">Jason van Zyl </a>
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 *
 * @see <a href="http://struts.apache.org/1.x/struts-taglib/indexedprops.html">
 * http://struts.apache.org/1.x/struts-taglib/indexedprops.html</a>
 */
public class ReflectionValueExtractor {
    private static final Class<?>[] CLASS_ARGS = new Class[0];

    private static final Object[] OBJECT_ARGS = new Object[0];

    /**
     * Use a WeakHashMap here, so the keys (Class objects) can be garbage collected.
     * This approach prevents permgen space overflows due to retention of discarded
     * classloaders.
     */
    private static final Map<Class<?>, ClassMap> CLASS_MAPS = new WeakHashMap<>();

    static final int EOF = -1;

    static final char PROPERTY_START = '.';

    static final char INDEXED_START = '[';

    static final char INDEXED_END = ']';

    static final char MAPPED_START = '(';

    static final char MAPPED_END = ')';

    static class Tokenizer {
        final String expression;

        int idx;

        Tokenizer(String expression) {
            this.expression = expression;
        }

        public int peekChar() {
            return idx < expression.length() ? expression.charAt(idx) : EOF;
        }

        public int skipChar() {
            return idx < expression.length() ? expression.charAt(idx++) : EOF;
        }

        public String nextToken(char delimiter) {
            int start = idx;

            while (idx < expression.length() && delimiter != expression.charAt(idx)) {
                idx++;
            }

            // delimiter MUST be present
            if (idx <= start || idx >= expression.length()) {
                return null;
            }

            return expression.substring(start, idx++);
        }

        public String nextPropertyName() {
            final int start = idx;

            while (idx < expression.length() && Character.isJavaIdentifierPart(expression.charAt(idx))) {
                idx++;
            }

            // property name does not require delimiter
            if (idx <= start || idx > expression.length()) {
                return null;
            }

            return expression.substring(start, idx);
        }

        public int getPosition() {
            return idx < expression.length() ? idx : EOF;
        }

        // to make tokenizer look pretty in debugger
        @Override
        public String toString() {
            return idx < expression.length() ? expression.substring(idx) : "<EOF>";
        }
    }

    private ReflectionValueExtractor() {}

    /**
     * <p>The implementation supports indexed, nested and mapped properties.</p>
     * <ul>
     * <li>nested properties should be defined by a dot, i.e. "user.address.street"</li>
     * <li>indexed properties (java.util.List or array instance) should be contains <code>(\\w+)\\[(\\d+)\\]</code>
     * pattern, i.e. "user.addresses[1].street"</li>
     * <li>mapped properties should be contains <code>(\\w+)\\((.+)\\)</code> pattern,
     *  i.e. "user.addresses(myAddress).street"</li>
     * </ul>
     *
     * @param expression not null expression
     * @param root       not null object
     * @return the object defined by the expression
     * @throws IntrospectionException if any
     */
    public static Object evaluate(@Nonnull String expression, @Nullable Object root) throws IntrospectionException {
        return evaluate(expression, root, true);
    }

    /**
     * <p>
     * The implementation supports indexed, nested and mapped properties.
     * </p>
     * <ul>
     * <li>nested properties should be defined by a dot, i.e. "user.address.street"</li>
     * <li>indexed properties (java.util.List or array instance) should be contains <code>(\\w+)\\[(\\d+)\\]</code>
     * pattern, i.e. "user.addresses[1].street"</li>
     * <li>mapped properties should be contains <code>(\\w+)\\((.+)\\)</code> pattern, i.e.
     * "user.addresses(myAddress).street"</li>
     * </ul>
     *
     * @param expression not null expression
     * @param root not null object
     * @param trimRootToken trim root token yes/no.
     * @return the object defined by the expression
     * @throws IntrospectionException if any
     */
    public static Object evaluate(@Nonnull String expression, @Nullable Object root, boolean trimRootToken)
            throws IntrospectionException {
        Object value = root;

        // ----------------------------------------------------------------------
        // Walk the dots and retrieve the ultimate value desired from the
        // MavenProject instance.
        // ----------------------------------------------------------------------

        if (StringUtils.isEmpty(expression) || !Character.isJavaIdentifierStart(expression.charAt(0))) {
            return null;
        }

        boolean hasDots = expression.indexOf(PROPERTY_START) >= 0;

        final Tokenizer tokenizer;
        if (trimRootToken && hasDots) {
            tokenizer = new Tokenizer(expression);
            tokenizer.nextPropertyName();
            if (tokenizer.getPosition() == EOF) {
                return null;
            }
        } else {
            tokenizer = new Tokenizer("." + expression);
        }

        int propertyPosition = tokenizer.getPosition();
        while (value != null && tokenizer.peekChar() != EOF) {
            switch (tokenizer.skipChar()) {
                case INDEXED_START:
                    value = getIndexedValue(
                            expression,
                            propertyPosition,
                            tokenizer.getPosition(),
                            value,
                            tokenizer.nextToken(INDEXED_END));
                    break;
                case MAPPED_START:
                    value = getMappedValue(
                            expression,
                            propertyPosition,
                            tokenizer.getPosition(),
                            value,
                            tokenizer.nextToken(MAPPED_END));
                    break;
                case PROPERTY_START:
                    propertyPosition = tokenizer.getPosition();
                    value = getPropertyValue(value, tokenizer.nextPropertyName());
                    break;
                default:
                    // could not parse expression
                    return null;
            }
        }

        return value;
    }

    private static Object getMappedValue(
            final String expression, final int from, final int to, final Object value, final String key)
            throws IntrospectionException {
        if (value == null || key == null) {
            return null;
        }

        if (value instanceof Map) {
            Object[] localParams = new Object[] {key};
            ClassMap classMap = getClassMap(value.getClass());
            try {
                Method method = classMap.findMethod("get", localParams);
                return method.invoke(value, localParams);
            } catch (AmbiguousException e) {
                throw new IntrospectionException(e);
            } catch (IllegalAccessException e) {
                throw new IntrospectionException(e);
            } catch (InvocationTargetException e) {
                throw new IntrospectionException(e.getTargetException());
            }
        }

        final String message = String.format(
                "The token '%s' at position '%d' refers to a java.util.Map, but the value "
                        + "seems is an instance of '%s'",
                expression.subSequence(from, to), from, value.getClass());

        throw new IntrospectionException(message);
    }

    private static Object getIndexedValue(
            final String expression, final int from, final int to, final Object value, final String indexStr)
            throws IntrospectionException {
        try {
            int index = Integer.parseInt(indexStr);

            if (value.getClass().isArray()) {
                return Array.get(value, index);
            }

            if (value instanceof List) {
                ClassMap classMap = getClassMap(value.getClass());
                // use get method on List interface
                Object[] localParams = new Object[] {index};
                Method method = null;
                try {
                    method = classMap.findMethod("get", localParams);
                    return method.invoke(value, localParams);
                } catch (AmbiguousException e) {
                    throw new IntrospectionException(e);
                } catch (IllegalAccessException e) {
                    throw new IntrospectionException(e);
                }
            }
        } catch (NumberFormatException e) {
            return null;
        } catch (InvocationTargetException e) {
            // catch array index issues gracefully, otherwise release
            if (e.getCause() instanceof IndexOutOfBoundsException) {
                return null;
            }

            throw new IntrospectionException(e.getTargetException());
        }

        final String message = String.format(
                "The token '%s' at position '%d' refers to a java.util.List or an array, but the value "
                        + "seems is an instance of '%s'",
                expression.subSequence(from, to), from, value.getClass());

        throw new IntrospectionException(message);
    }

    private static Object getPropertyValue(Object value, String property) throws IntrospectionException {
        if (value == null || property == null) {
            return null;
        }

        ClassMap classMap = getClassMap(value.getClass());
        String methodBase = StringUtils.capitalizeFirstLetter(property);
        String methodName = "get" + methodBase;
        try {
            Method method = classMap.findMethod(methodName, CLASS_ARGS);

            if (method == null) {
                // perhaps this is a boolean property??
                methodName = "is" + methodBase;

                method = classMap.findMethod(methodName, CLASS_ARGS);
            }

            if (method == null) {
                return null;
            }

            return method.invoke(value, OBJECT_ARGS);
        } catch (InvocationTargetException e) {
            throw new IntrospectionException(e.getTargetException());
        } catch (AmbiguousException e) {
            throw new IntrospectionException(e);
        } catch (IllegalAccessException e) {
            throw new IntrospectionException(e);
        }
    }

    private static ClassMap getClassMap(Class<?> clazz) {
        ClassMap classMap = CLASS_MAPS.get(clazz);

        if (classMap == null) {
            classMap = new ClassMap(clazz);

            CLASS_MAPS.put(clazz, classMap);
        }

        return classMap;
    }
}
