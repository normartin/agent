///usr/bin/env jbang "$0" "$@" ; exit $?
//KOTLIN 2.4.10
//DEPS org.jetbrains.kotlin:kotlin-stdlib:2.0.21

fun main(args: Array<String>) {
    println("Success! Running on Java 21 with Kotlin 2.0.21.")
    println("Passed arguments: ${args.joinToString()}")
}

