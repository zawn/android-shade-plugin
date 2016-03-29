/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.apache.commons.lang3.reflect;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.Validate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 用于通过反射调用方法.
 *
 * @author ZhangZhenli
 */
public class MethodInvokeUtils {

    public static Object invokeMethod(final Object object, final String methodName) throws NoSuchMethodException,
            IllegalAccessException, InvocationTargetException {
        return invokeMethod(object, methodName, ArrayUtils.EMPTY_OBJECT_ARRAY, null);
    }

    public static Object invokeMethod(final Object object, final String methodName,
                                      Object... args) throws NoSuchMethodException,
            IllegalAccessException, InvocationTargetException {
        args = ArrayUtils.nullToEmpty(args);
        final Class<?>[] parameterTypes = ClassUtils.toClass(args);
        return invokeMethod(object, methodName, args, parameterTypes);
    }

    public static Object invokeMethod(final Object object, final String methodName,
                                      Object[] args, Class<?>[] parameterTypes)
            throws NoSuchMethodException, IllegalAccessException,
            InvocationTargetException {
        parameterTypes = ArrayUtils.nullToEmpty(parameterTypes);
        args = ArrayUtils.nullToEmpty(args);
        final Method method = getMethod(object.getClass(),
                methodName, parameterTypes);
        if (method == null) {
            throw new NoSuchMethodException("No such accessible method: "
                    + methodName + argumentTypesToString(parameterTypes) + " on object: "
                    + object.getClass().getName());
        }
        return method.invoke(object, args);
    }

    public static Object invokeStaticMethod(final Class<?> cls, final String methodName,
                                            Object... args) throws NoSuchMethodException,
            IllegalAccessException, InvocationTargetException {
        args = ArrayUtils.nullToEmpty(args);
        final Class<?>[] parameterTypes = ClassUtils.toClass(args);
        return invokeStaticMethod(cls, methodName, args, parameterTypes);
    }

    public static Object invokeStaticMethod(final Class<?> cls, final String methodName,
                                            Object[] args, Class<?>[] parameterTypes)
            throws NoSuchMethodException, IllegalAccessException,
            InvocationTargetException {
        args = ArrayUtils.nullToEmpty(args);
        parameterTypes = ArrayUtils.nullToEmpty(parameterTypes);
        final Method method = getMethod(cls, methodName,
                parameterTypes);
        if (method == null) {
            throw new NoSuchMethodException("No such accessible method: "
                    + methodName + argumentTypesToString(parameterTypes) + " on class: " + cls.getName());
        }
        return method.invoke(null, args);
    }

    /**
     * 获取指定方法.
     *
     * @param cls
     * @param methodName
     * @param parameterTypes
     * @return
     * @throws NoSuchMethodException
     */
    public static Method getMethod(final Class<?> cls, final String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Validate.isTrue(cls != null, "The class must not be null");
        parameterTypes = ArrayUtils.nullToEmpty(parameterTypes);
        for (Class<?> acls = cls; acls != null; acls = acls.getSuperclass()) {
            final Method method = getMatchingAccessibleMethod(acls, methodName, parameterTypes);
            if (method != null) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    method.setAccessible(true);
                }
                return method;
            }

        }
        return null;
    }

    public static Method getMatchingAccessibleMethod(final Class<?> cls,
                                                     final String methodName, final Class<?>... parameterTypes) {
        try {
            final Method method = cls.getDeclaredMethod(methodName, parameterTypes);
            MemberUtils.setAccessibleWorkaround(method);
            return method;
        } catch (final NoSuchMethodException e) { // NOPMD - Swallow the exception
        }
        // search through all methods
        Method bestMatch = null;
        final Method[] methods = cls.getDeclaredMethods();
        for (final Method method : methods) {
            // compare name and parameters
            if (method.getName().equals(methodName) && ClassUtils.isAssignable(parameterTypes, method.getParameterTypes(), true)) {
                if (method != null && (bestMatch == null || MemberUtils.compareParameterTypes(
                        method.getParameterTypes(),
                        bestMatch.getParameterTypes(),
                        parameterTypes) < 0)) {
                    bestMatch = method;
                }
            }
        }
        if (bestMatch != null) {
            final Member m = (Member) bestMatch;
            if (!bestMatch.isAccessible() && Modifier.isPublic(m.getModifiers())) {
                try {
                    bestMatch.setAccessible(true);
                } catch (final SecurityException e) { // NOPMD
                    // ignore in favor of subsequent IllegalAccessException
                }
            }
        }
        return bestMatch;
    }

    private static String argumentTypesToString(Class<?>[] argTypes) {
        StringBuilder buf = new StringBuilder();
        buf.append("(");
        if (argTypes != null) {
            for (int i = 0; i < argTypes.length; i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                Class<?> c = argTypes[i];
                buf.append((c == null) ? "null" : c.getName());
            }
        }
        buf.append(")");
        return buf.toString();
    }

}
