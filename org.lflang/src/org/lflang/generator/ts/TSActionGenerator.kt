package org.lflang.generator.ts

import org.lflang.generator.PrependOperator
import org.lflang.lf.Action
import org.lflang.lf.Reactor
import org.lflang.lf.Type
import org.lflang.lf.Value
import java.util.*

class TSActionGenerator (
    // TODO(hokeun): Remove dependency on TSGenerator.
    private val tsGenerator: TSGenerator,
    private val reactor: Reactor
) {
    private fun Value.getTargetValue(): String = tsGenerator.getTargetValueW(this)
    private fun Type.getTargetType(): String = tsGenerator.getTargetTypeW(this)

    /**
     * Return a TS type for the specified action.
     * If the type has not been specified, return
     * "Present" which is the base type for Actions.
     * @param action The action
     * @return The TS type.
     */
    private fun getActionType(action: Action): String {
        if (action.type != null) {
            return action.type.getTargetType()
        } else {
            return "Present"
        }
    }

    fun generateClassProperties(): String {
        val stateClassProperties = LinkedList<String>()
        for (action in reactor.actions) {
            // Shutdown actions are handled internally by the
            // TypeScript reactor framework. There would be a
            // duplicate action if we included the one generated
            // by LF.
            if (action.name != "shutdown") {
                stateClassProperties.add("${action.name}: __Action<${getActionType(action)}>;")
            }
        }
        return with(PrependOperator) {
            """
            ${" |"..stateClassProperties.joinToString("\n")}
            """.trimMargin()
        }
    }

    fun generateInstantiations(): String {
        val actionInstantiations = LinkedList<String>()
        for (action in reactor.actions) {
            // Shutdown actions are handled internally by the
            // TypeScript reactor framework. There would be a
            // duplicate action if we included the one generated
            // by LF.
            if (action.name != "shutdown") {
                var actionArgs = "this, __Origin." + action.origin
                if (action.minDelay != null) {
                    // Actions in the TypeScript target are constructed
                    // with an optional minDelay argument which defaults to 0.
                    if (action.minDelay.parameter != null) {
                        actionArgs+= ", " + action.minDelay.parameter.name
                    } else {
                        actionArgs+= ", " + action.minDelay.getTargetValue()
                    }
                }
                actionInstantiations.add(
                    "this.${action.name} = new __Action<${getActionType(action)}>($actionArgs);")
            }
        }
        return with(PrependOperator) {
            """
            ${" |"..actionInstantiations.joinToString("\n")}
            """.trimMargin()
        }
    }
}