/*
 * The MIT License (MIT)
 * <p/>
 * Copyright (c) 2016 Vimeo
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.vimeo.stag.processor.generators;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.internal.bind.TreeTypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import com.vimeo.stag.KnownTypeAdapters;
import com.vimeo.stag.KnownTypeAdapters.ArrayTypeAdapter;
import com.vimeo.stag.processor.generators.model.AnnotatedClass;
import com.vimeo.stag.processor.generators.model.ClassInfo;
import com.vimeo.stag.processor.generators.model.SupportedTypesModel;
import com.vimeo.stag.processor.generators.model.accessor.FieldAccessor;
import com.vimeo.stag.processor.generators.typeadapter.ReadSpecGenerator;
import com.vimeo.stag.processor.generators.typeadapter.WriteSpecGenerator;
import com.vimeo.stag.processor.utils.ElementUtils;
import com.vimeo.stag.processor.utils.FileGenUtils;
import com.vimeo.stag.processor.utils.KnownTypeAdapterUtils;
import com.vimeo.stag.processor.utils.TypeUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;

public class TypeAdapterGenerator extends AdapterGenerator {

    private static final String TYPE_ADAPTER_FIELD_PREFIX = "mTypeAdapter";

    @NotNull
    private final ClassInfo mInfo;
    @NotNull
    private final SupportedTypesModel mSupportedTypesModel;
    private boolean mEnableSerializeNulls;

    public TypeAdapterGenerator(@NotNull SupportedTypesModel supportedTypesModel, @NotNull ClassInfo info, boolean enableSerializeNulls) {
        mSupportedTypesModel = supportedTypesModel;
        mInfo = info;
        mEnableSerializeNulls = enableSerializeNulls;
    }

    @NotNull
    private static TypeMirror getReplacedTypeMirror(@NotNull TypeMirror type, @NotNull Map<TypeMirror, TypeMirror> fieldTypeVarsMap) {
        if (type.getKind() == TypeKind.TYPEVAR) {
            return fieldTypeVarsMap.get(type);
        } else if (type instanceof DeclaredType) {
            DeclaredType declaredType = (DeclaredType) type;
            List<? extends TypeMirror> typeMirrors = declaredType.getTypeArguments();
            TypeMirror[] args = new TypeMirror[typeMirrors.size()];
            for (int idx = 0; idx < typeMirrors.size(); idx++) {
                args[idx] = getReplacedTypeMirror(typeMirrors.get(idx), fieldTypeVarsMap);
                idx++;
            }
            return TypeUtils.getDeclaredType(ElementUtils.getTypeElementFromQualifiedName(declaredType.asElement().toString()), args);
        } else {
            return type;
        }
    }

    /**
     * This is used to generate the type token code for the types that are unknown.
     */
    @Nullable
    private static String getTypeTokenCode(@NotNull TypeMirror fieldType,
                                           @NotNull StagGenerator stagGenerator,
                                           @NotNull Map<TypeMirror, String> typeVarsMap,
                                           @NotNull AdapterFieldInfo adapterFieldInfo) {
        if (fieldType.getKind() == TypeKind.TYPEVAR) {
            return adapterFieldInfo.updateAndGetTypeTokenFieldName(fieldType, "(com.google.gson.reflect.TypeToken<" + fieldType.toString() + ">) com.google.gson.reflect.TypeToken.get(" + typeVarsMap.get(fieldType) + ")");
        } else if (!TypeUtils.isParameterizedType(fieldType)) {
            ClassInfo classInfo = stagGenerator.getKnownClass(fieldType);
            if (classInfo != null) {
                return classInfo.getTypeAdapterQualifiedClassName() + ".TYPE_TOKEN";
            } else {
                return adapterFieldInfo.updateAndGetTypeTokenFieldName(fieldType, "com.google.gson.reflect.TypeToken.get(" + fieldType.toString() + ".class)");
            }
        } else if (fieldType instanceof DeclaredType) {
            /*
             * If it is of ParameterizedType, {@link com.vimeo.stag.utils.ParameterizedTypeUtil} is used to get the
             * type token of the parameter type.
             */
            DeclaredType declaredFieldType = (DeclaredType) fieldType;
            List<? extends TypeMirror> typeMirrors = ((DeclaredType) fieldType).getTypeArguments();
            StringBuilder result = new StringBuilder("(com.google.gson.reflect.TypeToken<" + fieldType.toString() + ">)com.google.gson.reflect.TypeToken.getParameterized(" +
                    declaredFieldType.asElement().toString() + ".class");

            /*
             * Iterate through all the types from the typeArguments and generate type token code accordingly
             */
            Map<TypeMirror, TypeMirror> fieldTypeVarsMap = new LinkedHashMap<>(typeMirrors.size());
            List<? extends TypeMirror> classArguments = TypeUtils.getTypeArguments(declaredFieldType.asElement().asType());

            int paramIndex = 0;
            for (TypeMirror parameterTypeMirror : typeMirrors) {
                TypeMirror classArg = classArguments != null && classArguments.size() > paramIndex
                        ? classArguments.get(paramIndex)
                        : null;
                if (classArg != null) {
                    fieldTypeVarsMap.put(classArg, parameterTypeMirror);
                }
                if (parameterTypeMirror.getKind() == TypeKind.WILDCARD) {
                    String upperBoundString = "";
                    if (classArg != null && classArg.getKind() == TypeKind.TYPEVAR) {
                        TypeVariable typeVariable = (TypeVariable) classArg;
                        TypeMirror upperBound = typeVariable.getUpperBound();
                        if (TypeUtils.isParameterizedType(upperBound)) {
                            TypeMirror replacedQualifiedType = getReplacedTypeMirror(upperBound, fieldTypeVarsMap);
                            fieldTypeVarsMap.put(classArg, replacedQualifiedType);
                            upperBoundString += getTypeTokenCode(replacedQualifiedType, stagGenerator, typeVarsMap, adapterFieldInfo) + ".getType()";
                        } else {
                            upperBoundString += upperBound.toString() + ".class";
                            fieldTypeVarsMap.put(classArg, upperBound);
                        }
                    }
                    result.append(", " + "com.vimeo.stag.Types.getWildcardType(new java.lang.reflect.Type[]{").append(upperBoundString).append("} , new java.lang.reflect.Type[]{})");
                } else if (parameterTypeMirror.getKind() != TypeKind.TYPEVAR && !TypeUtils.isParameterizedType(parameterTypeMirror)) {
                    // Optimize so that we do not have to call TypeToken.getType()
                    // When the class is non parametrized and we can call xxxxx.class directly
                    result.append(", ").append(parameterTypeMirror.toString()).append(".class");
                } else {
                    result.append(", ").append(getTypeTokenCode(parameterTypeMirror, stagGenerator, typeVarsMap, adapterFieldInfo)).append(".getType()");
                }
                paramIndex++;
            }
            result.append(")");
            return adapterFieldInfo.updateAndGetTypeTokenFieldName(fieldType, result.toString());
        } else {
            return adapterFieldInfo.updateAndGetTypeTokenFieldName(fieldType, "com.google.gson.reflect.TypeToken.get(" + fieldType.toString() + ".class)");
        }
    }

    @NotNull
    private static TypeName getAdapterFieldTypeName(@NotNull TypeMirror type) {
        TypeName typeName = TypeVariableName.get(type);
        return ParameterizedTypeName.get(ClassName.get(TypeAdapter.class), typeName);
    }

    @NotNull
    private static TypeName getTypeTokenFieldTypeName(@NotNull TypeMirror type) {
        TypeName typeName = TypeVariableName.get(type);
        return ParameterizedTypeName.get(ClassName.get(TypeToken.class), typeName);
    }

    @NotNull
    private static String getInitializationCodeForKnownJsonAdapterType(@NotNull ExecutableElement adapterType,
                                                                       @NotNull StagGenerator stagGenerator,
                                                                       @NotNull Map<TypeMirror, String> typeVarsMap,
                                                                       @NotNull MethodSpec.Builder constructorBuilder,
                                                                       @NotNull TypeMirror fieldType,
                                                                       @NotNull TypeUtils.JsonAdapterType jsonAdapterType,
                                                                       @NotNull AdapterFieldInfo adapterFieldInfo,
                                                                       boolean isNullSafe,
                                                                       @NotNull String keyFieldName) {
        String fieldAdapterAccessor = "new " + FileGenUtils.escapeStringForCodeBlock(adapterType.getEnclosingElement().toString());
        if (jsonAdapterType == TypeUtils.JsonAdapterType.TYPE_ADAPTER) {
            ArrayList<String> constructorParameters = new ArrayList<>();
            if (!adapterType.getParameters().isEmpty()) {
                for (VariableElement parameter : adapterType.getParameters()) {
                    if (parameter.asType().toString().equals(TypeUtils.className(Gson.class))) {
                        constructorParameters.add("gson");
                    } else {
                        throw new IllegalStateException("Not supported " + parameter.asType() + "parameter for @JsonAdapter value");
                    }
                }
            }


            StringBuilder constructorParameterStr = new StringBuilder("(");
            for (int i = 0; i < constructorParameters.size(); i++) {
                constructorParameterStr.append(constructorParameters.get(i));
                if (i != constructorParameters.size() - 1) {
                    constructorParameterStr.append(",");
                }
            }
            constructorParameterStr.append(")");
            fieldAdapterAccessor += constructorParameterStr;
        } else if (jsonAdapterType == TypeUtils.JsonAdapterType.TYPE_ADAPTER_FACTORY) {
            String typeTokenAccessorCode = getTypeTokenCode(fieldType, stagGenerator, typeVarsMap, adapterFieldInfo);
            fieldAdapterAccessor += "().create(gson, " + typeTokenAccessorCode + ")";
        } else if (jsonAdapterType == TypeUtils.JsonAdapterType.JSON_SERIALIZER
                || jsonAdapterType == TypeUtils.JsonAdapterType.JSON_DESERIALIZER
                || jsonAdapterType == TypeUtils.JsonAdapterType.JSON_SERIALIZER_DESERIALIZER) {
            String serializer = null, deserializer = null;

            if (jsonAdapterType == TypeUtils.JsonAdapterType.JSON_SERIALIZER_DESERIALIZER) {
                String varName = keyFieldName + "SerializerDeserializer";
                String initializer = adapterType.getEnclosingElement().toString() + " " + varName + " = " +
                        "new " + adapterType;
                constructorBuilder.addStatement(initializer);
                serializer = varName;
                deserializer = varName;
            } else if (jsonAdapterType == TypeUtils.JsonAdapterType.JSON_SERIALIZER) {
                serializer = "new " + adapterType;
            } else if (jsonAdapterType == TypeUtils.JsonAdapterType.JSON_DESERIALIZER) {
                deserializer = "new " + adapterType;
            }
            String typeTokenAccessorCode = getTypeTokenCode(fieldType, stagGenerator, typeVarsMap, adapterFieldInfo);
            fieldAdapterAccessor = "new " + TypeVariableName.get(TreeTypeAdapter.class) + "(" + serializer + ", " + deserializer + ", gson, " + typeTokenAccessorCode + ", null)";
        } else {
            throw new IllegalArgumentException(
                    "@JsonAdapter value must be TypeAdapter, TypeAdapterFactory, "
                            + "JsonSerializer or JsonDeserializer reference.");
        }
        String adapterCode = getCleanedFieldInitializer(fieldAdapterAccessor);
        if (isNullSafe) {
            adapterCode += ".nullSafe()";
        }
        return adapterCode;
    }

    private static String getCleanedFieldInitializer(String code) {
        return code.replace("mStagFactory", "stagFactory").replace("mGson", "gson");
    }

    /**
     * Returns the adapter code for the unknown types.
     */
    private static String getAdapterForUnknownGenericType(@NotNull TypeMirror fieldType,
                                                          @NotNull StagGenerator stagGenerator,
                                                          @NotNull Map<TypeMirror, String> typeVarsMap,
                                                          @NotNull AdapterFieldInfo adapterFieldInfo) {

        String fieldName = adapterFieldInfo.getFieldName(fieldType);
        if (fieldName == null) {
            fieldName = TYPE_ADAPTER_FIELD_PREFIX + adapterFieldInfo.size();
            String fieldInitializationCode = "gson.getAdapter(" +
                    getTypeTokenCode(fieldType, stagGenerator, typeVarsMap, adapterFieldInfo) + ")";
            adapterFieldInfo.addField(fieldType, fieldName, fieldInitializationCode);
        }
        return fieldName;
    }

    /**
     * Returns the adapter code for the known types.
     */
    private static String getAdapterAccessor(@NotNull TypeMirror fieldType,
                                             @NotNull StagGenerator stagGenerator,
                                             @NotNull Map<TypeMirror, String> typeVarsMap,
                                             @NotNull AdapterFieldInfo adapterFieldInfo) {

        String knownTypeAdapter = KnownTypeAdapterUtils.getKnownTypeAdapterForType(fieldType);

        if (knownTypeAdapter != null) {
            return knownTypeAdapter;
        }

        String fieldName = adapterFieldInfo.getFieldName(fieldType);
        if (fieldName != null) {
            return fieldName;
        }

        if (TypeUtils.isNativeArray(fieldType)) {
            /*
             * If the fieldType is of type native arrays such as String[] or int[]
             */
            TypeMirror arrayInnerType = TypeUtils.getArrayInnerType(fieldType);
            if (TypeUtils.isSupportedPrimitive(arrayInnerType.toString())) {
                return KnownTypeAdapterUtils.getNativePrimitiveArrayTypeAdapter(fieldType);
            } else {
                String adapterAccessor = getAdapterAccessor(arrayInnerType, stagGenerator, typeVarsMap,
                        adapterFieldInfo);
                String nativeArrayInstantiator =
                        KnownTypeAdapterUtils.getNativeArrayInstantiator(arrayInnerType);
                return "new " + TypeUtils.className(ArrayTypeAdapter.class) + "<" +
                        arrayInnerType.toString() + ">" +
                        "(" + adapterAccessor + ", " + nativeArrayInstantiator + ")";
            }
        } else if (TypeUtils.isSupportedList(fieldType)) {
            DeclaredType declaredType = (DeclaredType) fieldType;
            List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
            TypeMirror param = typeArguments.get(0);
            String paramAdapterAccessor = getAdapterAccessor(param, stagGenerator, typeVarsMap, adapterFieldInfo);
            String listInstantiator = KnownTypeAdapterUtils.getListInstantiator(fieldType);
            String adapterCode =
                    "new " + TypeUtils.className(KnownTypeAdapters.ListTypeAdapter.class) + "<" + param.toString() + "," +
                            fieldType.toString() + ">" +
                            "(" + paramAdapterAccessor + ", " + listInstantiator + ")";
            fieldName = TYPE_ADAPTER_FIELD_PREFIX + adapterFieldInfo.size();
            adapterFieldInfo.addField(fieldType, fieldName, adapterCode);
            return fieldName;

        } else if (TypeUtils.isSupportedMap(fieldType)) {
            DeclaredType declaredType = (DeclaredType) fieldType;
            String mapInstantiator = KnownTypeAdapterUtils.getMapInstantiator(fieldType);
            List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
            String keyAdapterAccessor;
            String valueAdapterAccessor;
            String arguments = "";
            if (typeArguments != null && typeArguments.size() == 2) {
                TypeMirror keyType = typeArguments.get(0);
                TypeMirror valueType = typeArguments.get(1);
                keyAdapterAccessor = getAdapterAccessor(keyType, stagGenerator, typeVarsMap, adapterFieldInfo);
                valueAdapterAccessor = getAdapterAccessor(valueType, stagGenerator, typeVarsMap, adapterFieldInfo);
                arguments = "<" + keyType.toString() + ", " + valueType.toString() + ", " +
                        fieldType.toString() + ">";
            } else {
                // If the map does not have any type arguments, use Object as type params in this case
                keyAdapterAccessor = "new com.vimeo.stag.KnownTypeAdapters.ObjectTypeAdapter(mGson)";
                valueAdapterAccessor = keyAdapterAccessor;
            }

            String adapterCode = "new " + TypeUtils.className(KnownTypeAdapters.MapTypeAdapter.class) + arguments +
                    "(" + keyAdapterAccessor + ", " + valueAdapterAccessor + ", " +
                    mapInstantiator + ")";
            fieldName = TYPE_ADAPTER_FIELD_PREFIX + adapterFieldInfo.size();
            adapterFieldInfo.addField(fieldType, fieldName, adapterCode);
            return fieldName;
        } else {
            return getAdapterForUnknownGenericType(fieldType, stagGenerator, typeVarsMap, adapterFieldInfo);
        }
    }

    @NotNull
    private static AdapterFieldInfo addAdapterFields(@NotNull StagGenerator stagGenerator,
                                                     @NotNull MethodSpec.Builder constructorBuilder,
                                                     @NotNull Map<FieldAccessor, TypeMirror> memberVariables,
                                                     @NotNull Map<TypeMirror, String> typeVarsMap) {

        AdapterFieldInfo result = new AdapterFieldInfo(memberVariables.size());
        for (Map.Entry<FieldAccessor, TypeMirror> entry : memberVariables.entrySet()) {
            FieldAccessor fieldAccessor = entry.getKey();
            TypeMirror fieldType = entry.getValue();

            String adapterAccessor = null;
            TypeMirror optionalJsonAdapter = fieldAccessor.getJsonAdapterType();
            if (optionalJsonAdapter != null) {
                ExecutableElement constructor = ElementUtils.getFirstConstructor(optionalJsonAdapter);
                if (constructor != null) {
                    TypeUtils.JsonAdapterType jsonAdapterType1 = TypeUtils.getJsonAdapterType(optionalJsonAdapter);
                    String initiazationCode = getInitializationCodeForKnownJsonAdapterType(constructor, stagGenerator,
                            typeVarsMap, constructorBuilder, fieldType,
                            jsonAdapterType1, result, fieldAccessor.isJsonAdapterNullSafe(), fieldAccessor.getJsonName());

                    String fieldName = TYPE_ADAPTER_FIELD_PREFIX + result.size();
                    result.addFieldToAccessor(fieldAccessor.getJsonName(), fieldName, fieldType, initiazationCode);
                } else {
                    throw new IllegalStateException("Unsupported @JsonAdapter value: " + optionalJsonAdapter);
                }
            } else if (KnownTypeAdapterUtils.hasNativePrimitiveTypeAdapter(fieldType)) {
                adapterAccessor = KnownTypeAdapterUtils.getNativePrimitiveTypeAdapter(fieldType);
            } else if (TypeUtils.containsTypeVarParams(fieldType)) {
                adapterAccessor = getAdapterForUnknownGenericType(fieldType, stagGenerator, typeVarsMap, result);
            } else {
                adapterAccessor = getAdapterAccessor(fieldType, stagGenerator, typeVarsMap, result);
            }

            if (adapterAccessor != null) {
                result.addTypeToAdapterAccessor(fieldType, adapterAccessor);
            }
        }
        return result;
    }

    /**
     * Generates the TypeSpec for the TypeAdapter
     * that this class generates.
     *
     * @return a valid TypeSpec that can be written
     * to a file or added to another class.
     */
    @Override
    @NotNull
    public TypeSpec createTypeAdapterSpec(@NotNull StagGenerator stagGenerator) {
        TypeMirror typeMirror = mInfo.getType();
        TypeName typeVariableName = TypeVariableName.get(typeMirror);

        List<? extends TypeMirror> typeArguments = mInfo.getTypeArguments();

        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(Gson.class, "gson");

        String className = FileGenUtils.unescapeEscapedString(mInfo.getTypeAdapterClassName());
        TypeSpec.Builder adapterBuilder = TypeSpec.classBuilder(className)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "\"unchecked\"")
                        .addMember("value", "\"rawtypes\"")
                        .build())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(ParameterizedTypeName.get(ClassName.get(TypeAdapter.class), typeVariableName));

        Map<TypeMirror, String> typeVarsMap = new LinkedHashMap<>();

        int idx = 0;
        if (typeArguments != null) {
            for (TypeMirror innerTypeMirror : typeArguments) {
                if (innerTypeMirror.getKind() == TypeKind.TYPEVAR) {
                    TypeVariable typeVariable = (TypeVariable) innerTypeMirror;
                    String simpleName = typeVariable.asElement().getSimpleName().toString();
                    adapterBuilder.addTypeVariable(TypeVariableName.get(simpleName, TypeVariableName.get(typeVariable.getUpperBound())));
                    //If the classInfo has unknown types, pass type... as param in constructor.
                    String paramName = "type[" + String.valueOf(idx) + "]";
                    typeVarsMap.put(typeVariable, paramName);
                    idx++;
                }
            }
        }

        if (idx > 0) {
            constructorBuilder.addParameter(Type[].class, "type");
            constructorBuilder.varargs(true);
        } else {
            //Create Type token as a static public final member variable
            // to be used from outside and by other adapters
            adapterBuilder.addField(createTypeTokenSpec(typeMirror));
        }

        AnnotatedClass annotatedClass = mSupportedTypesModel.getSupportedType(typeMirror);
        if (annotatedClass == null) {
            throw new IllegalStateException("The AnnotatedClass class can't be null in TypeAdapterGenerator : " + typeMirror.toString());
        }
        Map<FieldAccessor, TypeMirror> memberVariables = annotatedClass.getMemberVariables();

        AdapterFieldInfo adapterFieldInfo =
                addAdapterFields(stagGenerator, constructorBuilder, memberVariables, typeVarsMap);

        MethodSpec writeMethod = WriteSpecGenerator.getWriteMethodSpec(typeVariableName, memberVariables, adapterFieldInfo, mEnableSerializeNulls);
        MethodSpec readMethod = ReadSpecGenerator.getReadMethodSpec(typeVariableName, memberVariables, adapterFieldInfo);

        adapterBuilder.addField(Gson.class, "mGson", Modifier.FINAL, Modifier.PRIVATE);
        constructorBuilder.addStatement("this.mGson = gson");

        for (Map.Entry<String, FieldInfo> fieldInfo : adapterFieldInfo.mTypeTokenAccessorFields.entrySet()) {
            String originalFieldName = FileGenUtils.unescapeEscapedString(fieldInfo.getValue().accessorVariable);
            TypeName typeName = getTypeTokenFieldTypeName(fieldInfo.getValue().type);
            constructorBuilder.addStatement(typeName.toString() + " " + originalFieldName + " = " + fieldInfo.getValue().initializationCode);
        }

        for (Map.Entry<String, FieldInfo> fieldInfo : adapterFieldInfo.mFieldAdapterAccessor.entrySet()) {
            String originalFieldName = FileGenUtils.unescapeEscapedString(fieldInfo.getValue().accessorVariable);
            TypeName typeName = getAdapterFieldTypeName(fieldInfo.getValue().type);
            adapterBuilder.addField(typeName, originalFieldName, Modifier.PRIVATE, Modifier.FINAL);
            constructorBuilder.addStatement("this." + originalFieldName + " = " + fieldInfo.getValue().initializationCode);
        }

        for (Map.Entry<String, FieldInfo> fieldInfo : adapterFieldInfo.mAdapterFields.entrySet()) {
            String originalFieldName = FileGenUtils.unescapeEscapedString(fieldInfo.getValue().accessorVariable);
            TypeName typeName = getAdapterFieldTypeName(fieldInfo.getValue().type);
            adapterBuilder.addField(typeName, originalFieldName, Modifier.PRIVATE, Modifier.FINAL);
            constructorBuilder.addStatement("this." + originalFieldName + " = " + fieldInfo.getValue().initializationCode);
        }

        adapterBuilder.addMethod(constructorBuilder.build());
        adapterBuilder.addMethod(writeMethod);
        adapterBuilder.addMethod(readMethod);

        return adapterBuilder.build();
    }

    private static class FieldInfo {

        @NotNull
        final TypeMirror type;
        @NotNull
        final String initializationCode;
        @NotNull
        final String accessorVariable;

        FieldInfo(@NotNull TypeMirror type, @NotNull String initializationCode, @NotNull String accessorVariable) {
            this.type = type;
            this.initializationCode = initializationCode;
            this.accessorVariable = accessorVariable;
        }
    }

    public static class AdapterFieldInfo {

        //FieldName -> Accessor Map
        @NotNull
        final Map<String, FieldInfo> mFieldAdapterAccessor;
        //Type.toString -> Accessor Map
        @NotNull
        final Map<String, FieldInfo> mAdapterFields;
        //Type.toString -> Type Token Accessor Map
        @NotNull
        final Map<String, FieldInfo> mTypeTokenAccessorFields;
        //Type.toString -> Accessor Map
        @NotNull
        private final Map<String, String> mAdapterAccessor;

        AdapterFieldInfo(int capacity) {
            mAdapterFields = new LinkedHashMap<>(capacity);
            mAdapterAccessor = new LinkedHashMap<>(capacity);
            mFieldAdapterAccessor = new LinkedHashMap<>(capacity);
            mTypeTokenAccessorFields = new LinkedHashMap<>();
        }

        public String getAdapterAccessor(@NotNull TypeMirror typeMirror, @NotNull String fieldName) {
            FieldInfo adapterAccessor = mFieldAdapterAccessor.get(fieldName);
            return adapterAccessor != null ? adapterAccessor.accessorVariable : mAdapterAccessor.get(typeMirror.toString());
        }

        String updateAndGetTypeTokenFieldName(@NotNull TypeMirror fieldType, @NotNull String initializationCode) {
            FieldInfo result = mTypeTokenAccessorFields.get(fieldType.toString());
            if (result == null) {
                result = new FieldInfo(fieldType, initializationCode, "typeToken" + mTypeTokenAccessorFields.size());
                mTypeTokenAccessorFields.put(fieldType.toString(), result);
            }
            return result.accessorVariable;
        }

        String getFieldName(@NotNull TypeMirror fieldType) {
            FieldInfo fieldInfo = mAdapterFields.get(fieldType.toString());
            return fieldInfo != null ? fieldInfo.accessorVariable : null;
        }

        int size() {
            return mAdapterFields.size() + mFieldAdapterAccessor.size();
        }

        void addField(@NotNull TypeMirror fieldType, @NotNull String fieldName, @NotNull String fieldInitializationCode) {
            mAdapterFields.put(fieldType.toString(), new FieldInfo(fieldType, fieldInitializationCode, fieldName));
        }

        void addTypeToAdapterAccessor(@NotNull TypeMirror typeMirror, String accessorCode) {
            mAdapterAccessor.put(typeMirror.toString(), accessorCode);
        }

        void addFieldToAccessor(@NotNull String fieldName, @NotNull String variableName, TypeMirror fieldType, @NotNull String fieldInitializationCode) {
            mFieldAdapterAccessor.put(fieldName, new FieldInfo(fieldType, fieldInitializationCode, variableName));
        }
    }
}
