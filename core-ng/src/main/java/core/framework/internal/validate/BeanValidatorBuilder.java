package core.framework.internal.validate;

import core.framework.api.validate.Digits;
import core.framework.api.validate.Max;
import core.framework.api.validate.Min;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;
import core.framework.api.validate.Pattern;
import core.framework.api.validate.Size;
import core.framework.internal.asm.CodeBuilder;
import core.framework.internal.asm.DynamicInstanceBuilder;
import core.framework.internal.reflect.Classes;
import core.framework.internal.reflect.Fields;
import core.framework.internal.reflect.GenericTypes;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.regex.PatternSyntaxException;

import static core.framework.internal.asm.Literal.type;
import static core.framework.internal.asm.Literal.variable;
import static core.framework.util.Strings.format;

/**
 * @author neo
 */
public class BeanValidatorBuilder {
    private final Class<?> beanClass;
    DynamicInstanceBuilder<BeanValidator> builder;
    private int index = 0;

    public BeanValidatorBuilder(Class<?> beanClass) {
        this.beanClass = beanClass;
    }

    @Nullable
    public BeanValidator build() {
        validate(beanClass);
        if (Classes.instanceFields(beanClass).stream().noneMatch(this::hasValidationAnnotation)) return null;

        builder = new DynamicInstanceBuilder<>(BeanValidator.class, beanClass.getName() + "$Validator");
        String method = validateMethod(beanClass, null);
        var code = new CodeBuilder().append("public void validate(Object instance, {} errors, boolean partial) {\n", type(ValidationErrors.class));
        code.indent(1).append("{}(({}) instance, errors, partial);\n", method, type(beanClass));
        code.append('}');
        builder.addMethod(code.build());
        return builder.build();
    }

    private String validateMethod(Class<?> beanClass, String parentPath) {
        String methodName = "validate" + beanClass.getSimpleName() + (index++);
        var builder = new CodeBuilder().append("private void {}({} bean, {} errors, boolean partial) {\n", methodName, type(beanClass), type(ValidationErrors.class));
        for (Field field : Classes.instanceFields(beanClass)) {
            if (!hasValidationAnnotation(field)) continue;

            Type fieldType = field.getGenericType();
            Class<?> fieldClass = GenericTypes.rawClass(fieldType);
            String pathLiteral = variable(path(field, parentPath));

            builder.indent(1).append("if (bean.{} == null) {\n", field.getName());
            NotNull notNull = field.getDeclaredAnnotation(NotNull.class);
            if (notNull != null)
                builder.indent(2).append("if (!partial) errors.add({}, {}, null);\n", pathLiteral, variable(notNull.message()));
            builder.indent(1).append("} else {\n");

            if (String.class.equals(fieldClass)) {
                buildStringValidation(builder, field, pathLiteral);
            } else if (GenericTypes.isList(fieldType)) {
                buildListValidation(builder, field, pathLiteral, parentPath);
            } else if (GenericTypes.isMap(fieldType)) {
                buildMapValidation(builder, field, pathLiteral, parentPath);
            } else if (Number.class.isAssignableFrom(fieldClass)) {
                buildNumberValidation(builder, field, pathLiteral);
            } else if (!isValueClass(fieldClass)) {
                String method = validateMethod(fieldClass, path(field, parentPath));
                builder.indent(2).append("{}(bean.{}, errors, partial);\n", method, field.getName());
            }

            builder.indent(1).append("}\n");
        }
        builder.append('}');
        this.builder.addMethod(builder.build());
        return methodName;
    }

    private void buildNumberValidation(CodeBuilder builder, Field field, String pathLiteral) {
        Min min = field.getDeclaredAnnotation(Min.class);
        if (min != null)
            builder.indent(2).append("if (bean.{}.doubleValue() < {}) errors.add({}, {}, java.util.Map.of(\"value\", String.valueOf(bean.{}), \"min\", \"{}\"));\n",
                    field.getName(), min.value(), pathLiteral, variable(min.message()),
                    field.getName(), min.value());
        Max max = field.getDeclaredAnnotation(Max.class);
        if (max != null)
            builder.indent(2).append("if (bean.{}.doubleValue() > {}) errors.add({}, {}, java.util.Map.of(\"value\", String.valueOf(bean.{}), \"max\", \"{}\"));\n",
                    field.getName(), max.value(), pathLiteral, variable(max.message()),
                    field.getName(), max.value());
        Digits digits = field.getDeclaredAnnotation(Digits.class);
        if (digits != null) {
            int integer = digits.integer();
            int fraction = digits.fraction();
            builder.indent(2).append("java.math.BigDecimal number;\n")
                    .indent(2).append("if ((java.lang.Number) bean.{} instanceof java.math.BigDecimal) number = (java.math.BigDecimal) ((java.lang.Number) bean.{});\n", field.getName(), field.getName())
                    .indent(2).append("else number = new java.math.BigDecimal(bean.{}.toString()).stripTrailingZeros();\n", field.getName());
            if (digits.integer() > -1)
                builder.indent(2).append("int integerDigits = number.precision() - number.scale();\n")
                        .indent(2).append("if (integerDigits > {}) errors.add({}, {}, java.util.Map.of(\"value\", String.valueOf(bean.{}), \"integer\", \"{}\", \"fraction\", \"{}\"));\n",
                        integer, pathLiteral, variable(digits.message()),
                        field.getName(), integer, fraction == -1 ? "inf" : fraction);
            if (digits.fraction() > -1)
                builder.indent(2).append("int fractionDigits = number.scale() < 0 ? 0 : number.scale();\n")
                        .indent(2).append("if (fractionDigits > {}) errors.add({}, {}, java.util.Map.of(\"value\", String.valueOf(bean.{}), \"integer\", \"{}\", \"fraction\", \"{}\"));\n",
                        fraction, pathLiteral, variable(digits.message()),
                        field.getName(), integer == -1 ? "inf" : integer, fraction);
        }
    }

    private void buildMapValidation(CodeBuilder builder, Field field, String pathLiteral, String parentPath) {
        buildSizeValidation(builder, field, pathLiteral, "size");

        Type valueType = GenericTypes.mapValueType(field.getGenericType());
        if (GenericTypes.isList(valueType)) return; // ensured by class validator, if it's list it must be List<Value>

        Class<?> valueClass = GenericTypes.rawClass(valueType);
        if (!isValueClass(valueClass)) {
            String method = validateMethod(valueClass, path(field, parentPath));
            builder.indent(2).append("for (java.util.Iterator iterator = bean.{}.entrySet().iterator(); iterator.hasNext(); ) {\n", field.getName())
                    .indent(3).append("java.util.Map.Entry entry = (java.util.Map.Entry) iterator.next();\n")
                    .indent(3).append("{} value = ({}) entry.getValue();\n", type(valueClass), type(valueClass))
                    .indent(3).append("if (value != null) {}(value, errors, partial);\n", method)
                    .indent(2).append("}\n");
        }
    }

    private void buildListValidation(CodeBuilder builder, Field field, String pathLiteral, String parentPath) {
        buildSizeValidation(builder, field, pathLiteral, "size");

        Class<?> valueClass = GenericTypes.listValueClass(field.getGenericType());
        if (!isValueClass(valueClass)) {
            String method = validateMethod(valueClass, path(field, parentPath));
            builder.indent(2).append("for (java.util.Iterator iterator = bean.{}.iterator(); iterator.hasNext(); ) {\n", field.getName())
                    .indent(3).append("{} value = ({}) iterator.next();\n", type(valueClass), type(valueClass))
                    .indent(3).append("if (value != null) {}(value, errors, partial);\n", method)
                    .indent(2).append("}\n");
        }
    }

    private void buildStringValidation(CodeBuilder builder, Field field, String pathLiteral) {
        NotBlank notBlank = field.getDeclaredAnnotation(NotBlank.class);
        if (notBlank != null) builder.indent(2).append("if (bean.{}.isBlank()) errors.add({}, {}, null);\n", field.getName(), pathLiteral, variable(notBlank.message()));

        buildSizeValidation(builder, field, pathLiteral, "length");

        Pattern pattern = field.getDeclaredAnnotation(Pattern.class);
        if (pattern != null) {
            String patternFieldName = field.getName() + "Pattern" + (index++);
            String patternVariable = variable(pattern.value());
            this.builder.addField("private final java.util.regex.Pattern {} = java.util.regex.Pattern.compile({});", patternFieldName, patternVariable);
            builder.indent(2).append("if (!this.{}.matcher(bean.{}).matches()) errors.add({}, {}, java.util.Map.of(\"value\", bean.{}, \"pattern\", {}));\n",
                    patternFieldName, field.getName(), pathLiteral, variable(pattern.message()),
                    field.getName(), patternVariable);
        }
    }

    private void buildSizeValidation(CodeBuilder builder, Field field, String pathLiteral, String sizeMethod) {
        Size size = field.getDeclaredAnnotation(Size.class);
        if (size != null) {
            int min = size.min();
            int max = size.max();
            if (min > -1)
                builder.indent(2).append("if (bean.{}.{}() < {}) errors.add({}, {}, java.util.Map.of(\"value\", String.valueOf(bean.{}.{}()), \"min\", \"{}\", \"max\", \"{}\"));\n",
                        field.getName(), sizeMethod, min, pathLiteral, variable(size.message()),
                        field.getName(), sizeMethod, min, max == -1 ? "inf" : max);
            if (max > -1)
                builder.indent(2).append("if (bean.{}.{}() > {}) errors.add({}, {}, java.util.Map.of(\"value\", String.valueOf(bean.{}.{}()), \"min\", \"{}\", \"max\", \"{}\"));\n",
                        field.getName(), sizeMethod, max, pathLiteral, variable(size.message()),
                        field.getName(), sizeMethod, min == -1 ? "0" : min, max);
        }
    }

    private String path(Field field, String parentPath) {
        String path = field.getName();
        if (parentPath == null) return path;
        return parentPath + "." + path;
    }

    private void validate(Class<?> beanClass) {
        try {
            Object beanWithDefaultValue = beanClass.getDeclaredConstructor().newInstance();
            for (Field field : Classes.instanceFields(beanClass)) {
                validateAnnotations(field, beanWithDefaultValue);
                Class<?> targetClass = targetValidationClass(field);
                if (!isValueClass(targetClass))
                    validate(targetClass);
            }
        } catch (ReflectiveOperationException e) {
            throw new Error(e);
        }
    }

    private boolean hasValidationAnnotation(Field field) {
        boolean hasAnnotation = field.isAnnotationPresent(Digits.class)
                || field.isAnnotationPresent(NotNull.class)
                || field.isAnnotationPresent(NotBlank.class)
                || field.isAnnotationPresent(Max.class)
                || field.isAnnotationPresent(Min.class)
                || field.isAnnotationPresent(Pattern.class)
                || field.isAnnotationPresent(Size.class);
        if (hasAnnotation) return true;

        Class<?> targetClass = targetValidationClass(field);
        if (!isValueClass(targetClass)) {
            for (Field valueField : Classes.instanceFields(targetClass)) {
                if (hasValidationAnnotation(valueField)) return true;
            }
        }
        return false;
    }

    private Class<?> targetValidationClass(Field field) {
        Type fieldType = field.getGenericType();
        if (GenericTypes.isList(fieldType)) {
            return GenericTypes.listValueClass(fieldType);
        } else if (GenericTypes.isMap(fieldType)) {
            Type mapValueType = GenericTypes.mapValueType(fieldType);
            if (GenericTypes.isList(mapValueType)) {
                return GenericTypes.listValueClass(mapValueType);
            }
            return GenericTypes.rawClass(mapValueType);
        } else {
            return GenericTypes.rawClass(fieldType);
        }
    }

    private void validateAnnotations(Field field, Object beanWithDefaultValues) throws IllegalAccessException {
        Type fieldType = field.getGenericType();

        if (!field.isAnnotationPresent(NotNull.class) && field.get(beanWithDefaultValues) != null)
            throw new Error(format("field with default value must have @NotNull, field={}, fieldType={}", Fields.path(field), fieldType.getTypeName()));

        Size size = field.getDeclaredAnnotation(Size.class);
        if (size != null && !String.class.equals(fieldType) && !GenericTypes.isList(fieldType) && !GenericTypes.isMap(fieldType))
            throw new Error(format("@Size must on String, List<?> or Map<String, ?>, field={}, fieldType={}", Fields.path(field), fieldType.getTypeName()));

        NotBlank notBlank = field.getDeclaredAnnotation(NotBlank.class);
        if (notBlank != null && !String.class.equals(fieldType))
            throw new Error(format("@NotBlank must on String, field={}, fieldType={}", Fields.path(field), fieldType.getTypeName()));

        Pattern pattern = field.getDeclaredAnnotation(Pattern.class);
        if (pattern != null) {
            if (!String.class.equals(fieldType)) throw new Error(format("@Pattern must on String, field={}, fieldType={}", Fields.path(field), fieldType.getTypeName()));
            try {
                java.util.regex.Pattern.compile(pattern.value());
            } catch (PatternSyntaxException e) {
                throw new Error(format("@Pattern has invalid pattern, pattern={}, field={}, fieldType={}", pattern.value(), Fields.path(field), fieldType.getTypeName()), e);
            }
        }

        Class<?> fieldClass = GenericTypes.rawClass(fieldType);
        Max max = field.getDeclaredAnnotation(Max.class);
        if (max != null && !Number.class.isAssignableFrom(fieldClass))
            throw new Error(format("@Max must on Number, field={}, fieldType={}", Fields.path(field), fieldType.getTypeName()));

        Min min = field.getDeclaredAnnotation(Min.class);
        if (min != null && !Number.class.isAssignableFrom(fieldClass))
            throw new Error(format("@Min must on Number, field={}, fieldType={}", Fields.path(field), fieldType.getTypeName()));

        Digits digits = field.getDeclaredAnnotation(Digits.class);
        if (digits != null && !Number.class.isAssignableFrom(fieldClass))
            throw new Error(format("@Digits must on Number, field={}, fieldType={}", Fields.path(field), fieldType.getTypeName()));
    }

    private boolean isValueClass(Class<?> fieldClass) {
        return String.class.equals(fieldClass)
                || Number.class.isAssignableFrom(fieldClass)
                || Boolean.class.equals(fieldClass)
                || LocalDateTime.class.equals(fieldClass)
                || LocalDate.class.equals(fieldClass)
                || LocalTime.class.equals(fieldClass)
                || Instant.class.equals(fieldClass)
                || ZonedDateTime.class.equals(fieldClass)
                || fieldClass.isEnum()
                || "org.bson.types.ObjectId".equals(fieldClass.getCanonicalName()); // not depends on mongo jar if application doesn't include mongo driver
    }
}
