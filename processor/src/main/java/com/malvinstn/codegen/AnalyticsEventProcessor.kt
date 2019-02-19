package com.malvinstn.codegen

import com.google.auto.service.AutoService
import com.google.common.base.CaseFormat
import com.malvinstn.annotation.AnalyticsEvent
import com.squareup.kotlinpoet.*
import me.eugeniomarletti.kotlin.metadata.KotlinClassMetadata
import me.eugeniomarletti.kotlin.metadata.KotlinMetadataUtils
import me.eugeniomarletti.kotlin.metadata.kotlinMetadata
import me.eugeniomarletti.kotlin.metadata.modality
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf
import me.eugeniomarletti.kotlin.processing.KotlinAbstractProcessor
import java.io.File
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic

@AutoService(Processor::class)
class AnalyticsEventProcessor : KotlinAbstractProcessor(), KotlinMetadataUtils {
    companion object {
        private val ANNOTATION = AnalyticsEvent::class.java

        private const val EVENT_PARAMETER_NAME = "event"
        private const val EVENT_NAME_PARAMETER_NAME = "name"
        private const val EVENT_PARAM_PARAMETER_NAME = "params"

        private const val LOG_EVENT_FUNCTION_NAME = "logEvent"

        private val EVENT_TRACKER_CLASS = ClassName("com.malvinstn.tracker", "EventTracker")
        private val BUNDLE_CLASS = ClassName("android.os", "Bundle")
        private val BUNDLE_OF_FUNCTION = ClassName("androidx.core.os", "bundleOf")
    }


    override fun getSupportedAnnotationTypes() = setOf(ANNOTATION.canonicalName)

    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latestSupported()

    override fun process(
        annotations: Set<TypeElement>,
        roundEnv: RoundEnvironment
    ): Boolean {
        val outputDir = generatedDir
        if (outputDir == null) {
            messager.printMessage(
                Diagnostic.Kind.ERROR,
                "Cannot find generated output dir."
            )
            return false
        }

        // Get all elements that has been annotated with our annotation
        val annotatedElements = roundEnv.getElementsAnnotatedWith(ANNOTATION)

        for (annotatedElement in annotatedElements) {
            // Check if the annotatedElement is a Kotlin sealed class
            val analyticsElement = getAnalyticsElement(annotatedElement) ?: continue

            // Get all the declared inner class as our Analytics Event
            val declaredAnalyticsEvents = getDeclaredAnalyticsEvents(analyticsElement)

            if (declaredAnalyticsEvents.isEmpty()) {
                // No declared Analytics Event, skip this class.
                messager.printMessage(
                    Diagnostic.Kind.WARNING,
                    "$analyticsElement has no valid inner class."
                )
                continue
            }

            // Generate codes with KotlinPoet
            generateCode(analyticsElement, declaredAnalyticsEvents, outputDir)
        }
        return true
    }

    private fun getAnalyticsElement(element: Element): TypeElement? {
        val kotlinMetadata = element.kotlinMetadata
        if (kotlinMetadata !is KotlinClassMetadata || element !is TypeElement) {
            // Not a Kotlin class
            messager.printMessage(
                Diagnostic.Kind.WARNING,
                "$element is not a Kotlin class."
            )
            return null
        }
        val proto = kotlinMetadata.data.classProto
        if (proto.modality != ProtoBuf.Modality.SEALED) {
            // Is not a sealed class
            messager.printMessage(
                Diagnostic.Kind.WARNING,
                "$element is not a sealed Kotlin class."
            )
            return null
        }
        return element
    }

    private fun getDeclaredAnalyticsEvents(
        analyticsElement: TypeElement
    ): Map<ClassName, List<String>> {
        val analyticsEvents = mutableMapOf<ClassName, List<String>>()
        // Get all declared inner elements, but skip the last element
        // since the last element is the actual analyticsElement itself.
        val enclosedElements = analyticsElement.enclosedElements.dropLast(1)

        val supertype = analyticsElement.asType()

        for (element in enclosedElements) {

            val type = element.asType()
            val kotlinMetadata = element.kotlinMetadata

            if (kotlinMetadata !is KotlinClassMetadata || element !is TypeElement) {
                // Inner class is not a Kotlin class
                messager.printMessage(
                    Diagnostic.Kind.WARNING,
                    "$element is not a kotlin class."
                )
                continue
            } else if (!typeUtils.directSupertypes(type).contains(supertype)) {
                // Inner class does not extend from the enclosing sealed class
                messager.printMessage(
                    Diagnostic.Kind.WARNING,
                    "$element does not extend from $analyticsElement."
                )
                continue
            }

            // Make use of KotlinPoet's ClassName to easily get the class' name.
            val eventClass = element.asClassName()

            // Extract the primary constructor and its parameters as the event's parameters.
            val proto = kotlinMetadata.data.classProto
            val nameResolver = kotlinMetadata.data.nameResolver

            if (proto.constructorCount == 0) {
                messager.printMessage(
                    Diagnostic.Kind.WARNING,
                    "$element has no constructor."
                )
                continue
            }

            val mainConstructor = proto.constructorList[0]
            val eventParameters = mainConstructor.valueParameterList
                .map { valueParameter ->
                    // Resolve the constructor parameter's name
                    // using nameResolver.
                    nameResolver.getString(valueParameter.name)
                }

            analyticsEvents[eventClass] = eventParameters
        }
        return analyticsEvents
    }

    private fun generateCode(
        analyticsElement: TypeElement,
        analyticEvents: Map<ClassName, List<String>>,
        outputDir: File
    ) {
        val className = analyticsElement.asClassName()
        val extensionFunSpecBuilder = FunSpec.builder(LOG_EVENT_FUNCTION_NAME)
            .addKdoc(
                CodeBlock.builder()
                    .addStatement(
                        "Converts [%T] to event name and params and logs it using [%T.%L].",
                        className,
                        EVENT_TRACKER_CLASS,
                        LOG_EVENT_FUNCTION_NAME
                    )
                    .addStatement("")
                    .addStatement("This is a generated function. Do not edit.")
                    .build()
            )
            .receiver(EVENT_TRACKER_CLASS)
            .addParameter(EVENT_PARAMETER_NAME, className)
            .addStatement("val %L: %T", EVENT_NAME_PARAMETER_NAME, String::class)
            .addStatement("val %L: %T", EVENT_PARAM_PARAMETER_NAME, BUNDLE_CLASS)
            .beginControlFlow("when (%L)", EVENT_PARAMETER_NAME)

        for ((eventName, eventParamList) in analyticEvents) {
            val codeBlock = CodeBlock.builder()
                .addStatement("is %T -> {", eventName)
                .indent()
                .addStatement(
                    "%L = %S",
                    EVENT_NAME_PARAMETER_NAME,
                    eventName.simpleName.convertCase(
                        CaseFormat.UPPER_CAMEL,
                        CaseFormat.LOWER_UNDERSCORE
                    )
                )
                .apply {
                    if (eventParamList.isNotEmpty()) {
                        addStatement("%L = %T(", EVENT_PARAM_PARAMETER_NAME, BUNDLE_OF_FUNCTION)
                        indent()
                        for ((index, parameter) in eventParamList.withIndex()) {
                            val size = eventParamList.size
                            val separator = if (index == size - 1) {
                                ""
                            } else {
                                ","
                            }
                            addStatement(
                                "%S to %L.%L%L",
                                parameter.convertCase(
                                    CaseFormat.LOWER_CAMEL,
                                    CaseFormat.LOWER_UNDERSCORE
                                ),
                                EVENT_PARAMETER_NAME,
                                parameter,
                                separator
                            )
                        }
                        unindent()
                        addStatement(")")
                    } else {
                        addStatement("%L = %T()", EVENT_PARAM_PARAMETER_NAME, BUNDLE_CLASS)
                    }
                }
                .unindent()
                .addStatement("}")
                .build()

            extensionFunSpecBuilder.addCode(codeBlock)
        }
        extensionFunSpecBuilder.endControlFlow()
            .addStatement(
                "%L(%L, %L)",
                LOG_EVENT_FUNCTION_NAME,
                EVENT_NAME_PARAMETER_NAME,
                EVENT_PARAM_PARAMETER_NAME
            )


        FileSpec.builder(className.packageName, className.simpleName)
            .addFunction(extensionFunSpecBuilder.build())
            .build()
            .writeTo(outputDir)
    }

}

/**
 * Helper function to convert String case
 * using guava's [CaseFormat].
 */
private fun String.convertCase(
    fromCase: CaseFormat,
    toCase: CaseFormat
): String {
    return fromCase.to(toCase, this)
}