/*************
 * Copyright (c) 2021, TU Dresden.

 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:

 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.

 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.

 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
 * THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ***************/

package org.lflang.generator.cpp

import org.lflang.*
import org.lflang.generator.cpp.CppParameterGenerator.Companion.targetType
import org.lflang.lf.Instantiation
import org.lflang.lf.Parameter
import org.lflang.lf.Reactor

/** A code genarator for reactor instances */
class CppInstanceGenerator(
    private val reactor: Reactor,
    private val fileConfig: CppFileConfig,
) {
    private val Instantiation.isAffiliate: Boolean get() = AttributeUtils.isAffiliate(this)

    val Instantiation.cppType: String
        get() {
            return if (reactor.isGeneric)
                """${reactor.name}<${typeParms.joinToString(", ") { it.toText() }}>"""
            else
                reactor.name
        }

    private fun generateDeclaration(inst: Instantiation): String = with(inst) {
        return when {
            !isBank && !isAffiliate -> "std::unique_ptr<$cppType> $name;"
            isBank && !isAffiliate  -> "std::vector<std::unique_ptr<$cppType>> $name;"
            !isBank && isAffiliate  -> """
                reactor::Environment __lf_env_$name;
                std::thread __lf_thread_$name;
                std::unique_ptr<$cppType> $name;
            """.trimIndent()

            isBank && isAffiliate   -> TODO("Affiliated banks are not supported yet")
            else                    -> throw RuntimeException("Unexpected case")
        }
    }

    private fun Instantiation.getParameterValue(param: Parameter, isBankInstantiation: Boolean = false): String {
        val assignment = this.parameters.firstOrNull { it.lhs === param }

        return if (isBankInstantiation && param.name == "bank_index") {
            // If we are in a bank instantiation (instanceId != null), then assign the instanceId
            // to the parameter named "bank_index"
            """__lf_idx"""
        } else if (assignment == null) {
            // If no assignment was found, then the parameter is not overwritten and we assign the
            // default value
            with(CppParameterGenerator) { param.defaultValue }
        } else {
            // Otherwise, we use the assigned value.
            if (assignment.equals == "=") {
                if (!assignment.braces.isNullOrEmpty()) {
                    "{${assignment.rhs.joinToString(", ") { it.toCppCode() }}}"
                } else if (!assignment.parens.isNullOrEmpty()) {
                    "(${assignment.rhs.joinToString(", ") { it.toCppCode() }})"
                } else {
                    assert(assignment.rhs.size == 1)
                    assignment.rhs[0].toCppCode()
                }
            } else {
                if (!assignment.braces.isNullOrEmpty()) {
                    "${param.targetType}{${assignment.rhs.joinToString(", ") { it.toCppCode() }}}"
                } else {
                    "${param.targetType}(${assignment.rhs.joinToString(", ") { it.toCppCode() }})"
                }
            }
        }
    }

    private fun Instantiation.getParameterValues() = reactor.parameters.joinToString(", ") { getParameterValue(it) }

    private fun Instantiation.getUniquePointerInitializer(parentRef: String): String {
        val params = getParameterValues()
        return if (params.isEmpty())
            """std::make_unique<$cppType>("$name", $parentRef)"""
        else {
            """std::make_unique<$cppType>("$name", $parentRef, $params)"""
        }
    }

    private fun generateInitializer(inst: Instantiation): String {
        with(inst) {
            assert(!isBank)
            return if (!isAffiliate) ", $name(${getUniquePointerInitializer("this")})"
            else """
                , __lf_env_$name("$name", this->environment())
                , $name(${getUniquePointerInitializer("&__lf_env_$name")})
            """.trimIndent()
        }
    }

    private fun generateConstructorInitializer(inst: Instantiation): String {
        with(inst) {
            assert(isBank)
            assert(!isAffiliate)
            val emplaceLine = "$name.emplace_back(${getUniquePointerInitializer("this")});"

            val width = inst.widthSpec.toCppCode()
            return """
                // initialize instance $name
                $name.reserve($width);
                for (size_t __lf_idx = 0; __lf_idx < $width; __lf_idx++) {
                  std::string __lf_inst_name = "${name}_" + std::to_string(__lf_idx);
                  $emplaceLine
                }
            """.trimIndent()
        }
    }

    /** Generate C++ include statements for each reactor that is instantiated */
    fun generateIncludes(): String =
        reactor.instantiations.map { fileConfig.getReactorHeaderPath(it.reactor) }
            .distinct()
            .joinToString(separator = "\n") { """#include "${it.toUnixString()}" """ }

    /** Generate declaration statements for all reactor instantiations */
    fun generateDeclarations(): String {
        return reactor.instantiations.joinToString(
            prefix = "// reactor instances\n",
            separator = "\n"
        ) { generateDeclaration(it) }
    }

    fun generateConstructorInitializers() =
        reactor.instantiations.filter { it.isBank }.joinWithLn { generateConstructorInitializer(it) }

    /** Generate constructor initializers for all reactor instantiations */
    fun generateInitializers(): String =
        reactor.instantiations.filterNot { it.isBank }
            .joinToString(prefix = "//reactor instances\n", separator = "\n") { generateInitializer(it) }
}
