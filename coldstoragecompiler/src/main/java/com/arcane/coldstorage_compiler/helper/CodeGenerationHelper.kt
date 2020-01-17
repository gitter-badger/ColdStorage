package com.arcane.coldstorage_compiler.helper

import com.arcane.coldstorageannotation.Freeze
import com.arcane.coldstorageannotation.Refrigerate
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import java.util.stream.Collectors
import javax.lang.model.element.*
import javax.lang.model.type.ExecutableType
import kotlin.reflect.jvm.internal.impl.builtins.jvm.JavaToKotlinClassMap
import kotlin.reflect.jvm.internal.impl.name.FqName

/**
 * Helper class to generate the code for the annotation processor.
 *
 * @author Anurag.
 */
class CodeGenerationHelper {

    /**
     * Method that generated the wrapper method.
     */
    fun generateWrapperMethod(element: Element, annotation: Annotation): FunSpec {
        val executableType = element.asType() as ExecutableType
        val name = element.simpleName.toString()
        val parameterTypes = executableType.parameterTypes
        val executableElement = element as ExecutableElement
        val enclosingElement = executableElement.enclosingElement


        var counter = 0
        val parameterList = arrayListOf<ParameterSpec>()
        val declaringClass = (enclosingElement as TypeElement).qualifiedName.toString()
        val variables = arrayListOf<String>()

        parameterTypes.forEach { parameter ->
            val variableName = executableElement.parameters[counter]
                .simpleName.toString()

            val parameterSpec = ParameterSpec
                .builder(
                    variableName,
                    parameter.asTypeName().javaToKotlinType()
                )
                .build()
            parameterList.add(parameterSpec)
            variables.add(variableName)
            counter += 1
        }

        //adding the callback variable
        val callBack = ClassName(
            CALL_BACK_PACKAGE_NAME,
            CALL_BACK_INTERFACE_NAME
        )
        val parametrizedCallback = callBack.parameterizedBy(
            executableElement
                .returnType.asTypeName().javaToKotlinType().copy(nullable = true)
        )
        val callbackSpec = ParameterSpec.builder(
            CALL_BACK_PARAMETER_NAME, parametrizedCallback
        )
            .build()


        if (annotation is Freeze) {
            parameterList.add(callbackSpec)
            return FunSpec.builder(name)
                .addParameters(parameterList)
                .addCode(
                    generateCodeBlockForFreeze(
                        name,
                        variables,
                        executableElement
                            .returnType.asTypeName().javaToKotlinType(),
                        executableElement.parameters,
                        annotation
                    )
                )
                .build()
        } else {

            if (!element.modifiers.contains(Modifier.STATIC)) {
                val variableType = ClassName(
                    getPackageName(declaringClass),
                    getClassName(declaringClass)
                )
                val parameterSpec = ParameterSpec.builder(
                    GENERATED_OBJECT_PARAMETER_NAME, variableType
                )
                    .build()
                parameterList.add(parameterSpec)
                parameterList.add(callbackSpec)
            }

            return FunSpec.builder(name)
                .addParameters(parameterList)
                .addCode(
                    generateCodeBlockForRefrigerate(
                        name,
                        variables,
                        executableElement
                            .returnType.asTypeName().javaToKotlinType(),
                        annotation as Refrigerate
                        , executableElement.parameters
                    )
                )
                .build()
        }
    }

    /**
     * Converting java types to kotlin types.
     */
    private fun TypeName.javaToKotlinType(): TypeName = if (this is ParameterizedTypeName) {
        (rawType.javaToKotlinType() as ClassName).parameterizedBy(
            *typeArguments.map { it.javaToKotlinType() }.toTypedArray()
        )
    } else {
        val className = JavaToKotlinClassMap.INSTANCE
            .mapJavaToKotlin(FqName(toString()))?.asSingleFqName()?.asString()
        if (className == null) this
        else ClassName.bestGuess(className)
    }


    /**
     * Method to get the class name from FQFN.
     */
    private fun getClassName(fullyQualifiedName: String): String {
        return fullyQualifiedName.substring(
            fullyQualifiedName.lastIndexOf('.') + 1, fullyQualifiedName.length
        )

    }

    /**
     * Method to get the package name from FQFN.
     */
    private fun getPackageName(fullyQualifiedName: String): String {
        return fullyQualifiedName.substring(
            0,
            fullyQualifiedName.lastIndexOf('.')
        )

    }

    /**
     * Method that generates the code block inside each method.
     * The logic here is that the actual method will be called
     * if the value against the key is not available inside the
     * cache.
     */
    private fun generateCodeBlockForFreeze(
        name: String,
        variables: ArrayList<String>,
        returnType: Any,
        parameters: MutableList<out VariableElement>,
        freeze: Freeze
    ): CodeBlock {

        val timeToLive: Long? =
            if (freeze.timeToLive.toInt() == -1) {
                null
            } else {
                freeze.timeToLive
            }


        val coldStorageCache = ClassName(
            "com.arcane.coldstoragecache.cache",
            "ColdStorage"
        )

        var calculateKeyCodeBlock = "val $KEY_VARIABLE = "

        if (parameters.isNullOrEmpty()) {
            calculateKeyCodeBlock += " \"$name\""
        } else {
            parameters.forEach { key ->
                calculateKeyCodeBlock += "Integer.toString($key.hashCode())"
                if (parameters.indexOf(key) != parameters.size - 1) {
                    calculateKeyCodeBlock += " + "
                }
            }
        }


        val run = FunSpec.builder("run")
            .addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
            .returns(Void.TYPE)
            .addStatement(calculateKeyCodeBlock)
            .beginControlFlow(
                "if (%T.get(${KEY_VARIABLE}) == null)",
                coldStorageCache
            )
            .addStatement(
                "val generatedVariable = $GENERATED_OBJECT_PARAMETER_NAME" +
                        ".$name(${variables.joinToString(separator = ", ")})", returnType
            )
            .addStatement(
                "%T.put(${KEY_VARIABLE},generatedVariable,$timeToLive)",
                coldStorageCache
            )
            .addStatement(
                "${CALL_BACK_PARAMETER_NAME}.onSuccess(generatedVariable," +
                        "\"$name\")"
            )
            .nextControlFlow("else")
            .addStatement(
                "${CALL_BACK_PARAMETER_NAME}.onSuccess(" +
                        "%T.get(${KEY_VARIABLE}) as (%T),\"$name\")",
                coldStorageCache,
                returnType
            )
            .endControlFlow()
            .addAnnotation(Override::class.java).build()

        val runnable = TypeSpec.anonymousClassBuilder()
            .addSuperinterface(Runnable::class.java)
            .addFunction(run).build()


        return CodeBlock.builder()
            .addStatement(
                "%T( %L )", ClassName(
                    "android.os.AsyncTask",
                    "execute"
                ), runnable
            )
            .build()

    }

    /**
     * Method that generates the code block inside each method.
     * The logic here is that the actual method will be called
     * if the value against the key is not available inside the
     * cache.
     * This code generation block is specific for Refrigerate annotation.
     */
    private fun generateCodeBlockForRefrigerate(
        name: String,
        variables: ArrayList<String>,
        returnType: Any,
        refrigerate: Refrigerate,
        parameters: MutableList<out VariableElement>
    ): CodeBlock {


        val timeToLive: Long? =
            if (refrigerate.timeToLive < 0) {
                null
            } else {
                refrigerate.timeToLive
            }

        //TODO check if variable name is incorrect
        val keys: MutableList<String> =
            if (refrigerate.keys.isEmpty()) {
                parameters.stream().map { parameter ->
                    parameter.simpleName.toString()
                }.collect(Collectors.toList())
            } else {
                refrigerate.keys.toMutableList()
            }

        var calculateKeyCodeBlock = "val $KEY_VARIABLE = "

        if (keys.isNullOrEmpty()) {
            calculateKeyCodeBlock += " \"${refrigerate.operation}\""
        } else {
            keys.forEach { key ->
                calculateKeyCodeBlock += "Integer.toString($key.hashCode())"
                if (keys.indexOf(key) != keys.size - 1) {
                    calculateKeyCodeBlock += " + "
                }
            }
        }

        val coldStorageCache = ClassName(
            "com.arcane.coldstoragecache.cache",
            "ColdStorage"
        )


        val run = FunSpec.builder("run")
            .addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
            .returns(Void.TYPE)
            .addStatement(calculateKeyCodeBlock)
            .beginControlFlow(
                "if (%T.get(${KEY_VARIABLE}) == null)",
                coldStorageCache
            )
            .addStatement(
                "val generatedVariable = $GENERATED_OBJECT_PARAMETER_NAME" +
                        ".$name(${variables.joinToString(separator = ", ")})", returnType
            )
            .addStatement(
                "%T.put(${KEY_VARIABLE},generatedVariable,$timeToLive)",
                coldStorageCache
            )
            .addStatement(
                "${CALL_BACK_PARAMETER_NAME}.onSuccess(generatedVariable," +
                        "\"${refrigerate.operation}\")"
            )
            .nextControlFlow("else")
            .addStatement(
                "${CALL_BACK_PARAMETER_NAME}.onSuccess(" +
                        "%T.get(${KEY_VARIABLE}) as (%T),\"${refrigerate.operation}\")",
                coldStorageCache,
                returnType
            )
            .endControlFlow()
            .addAnnotation(Override::class.java).build()

        val runnable = TypeSpec.anonymousClassBuilder()
            .addSuperinterface(Runnable::class.java)
            .addFunction(run).build()


        return CodeBlock.builder()
            .addStatement(
                "%T( %L )", ClassName(
                    "android.os.AsyncTask",
                    "execute"
                ), runnable
            )
            .build()

    }

    /**
     * The static members of this class.
     */
    companion object {

        const val GENERATED_OBJECT_PARAMETER_NAME = "obj"

        const val CALL_BACK_PARAMETER_NAME = "callback"

        const val CALL_BACK_PACKAGE_NAME = "com.arcane.coldstoragecache.callback"

        const val CALL_BACK_INTERFACE_NAME = "OnOperationSuccessfulCallback"

        const val KEY_VARIABLE = "thisvariablenameisspecial"
    }

}