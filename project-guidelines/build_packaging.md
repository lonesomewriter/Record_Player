# This is a guideline regarding Builds and their Packaging


## Expectations
APKs, .debs, or any form of executable or build should go under "/project-builds"
* If "/project-builds" doesn't exist, create it. This is a strict folder structure you MUST follow.
    - If the build is a test/dev build: "/project-builds/dev-builds/(build-name)"
    - If the build is a release build: "/project-builds/release-builds/(build-name)""
* A "build summary" should be logged in "/project-builds/logs"

Always ask the user which type the build is(release, or dev), then send the build to it's respective folder

Builds MUST have properly bundled all proper dependencies/components required for it to run. Always check the valadity of said build(s) for any missing components, scripts, packages, or dependencies that otherwise prevent it from running on a machine.

Builds MUST be checked against all known issues / issues listed(see section: "Known Errors for Builds")

Check the naming convention of the build type. If it defers from what it's packaging expects, it WILL NOT WORK.

You are to NEVER clean old builds unless the user expresses EXPLICIT PERMISSION TO DO SO.

* The naming convention for the Build(s) folder(s) is as follows:
    - "(Release Name Here)-(Platform Type)-(Version)"


## Known Errors for Builds
### .debian packages
#### Kotlin standard library Error
```
Exception in thread "main" java.lang.NoClassDefFoundError: kotlin/coroutines/CoroutineContext$Element
	at java.base/java.lang.ClassLoader.defineClass1(Native Method)
	at java.base/java.lang.ClassLoader.defineClass(Unknown Source)
	at java.base/java.security.SecureClassLoader.defineClass(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.defineClass(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.findClassOnClassPathOrNull(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(Unknown Source)
	at java.base/java.lang.ClassLoader.loadClass(Unknown Source)
	at java.base/java.lang.ClassLoader.defineClass1(Native Method)
	at java.base/java.lang.ClassLoader.defineClass(Unknown Source)
	at java.base/java.security.SecureClassLoader.defineClass(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.defineClass(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.findClassOnClassPathOrNull(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(Unknown Source)
	at java.base/java.lang.ClassLoader.loadClass(Unknown Source)
	at java.base/java.lang.ClassLoader.defineClass1(Native Method)
	at java.base/java.lang.ClassLoader.defineClass(Unknown Source)
	at java.base/java.security.SecureClassLoader.defineClass(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.defineClass(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.findClassOnClassPathOrNull(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(Unknown Source)
	at java.base/java.lang.ClassLoader.loadClass(Unknown Source)
	at java.base/java.lang.ClassLoader.defineClass1(Native Method)
	at java.base/java.lang.ClassLoader.defineClass(Unknown Source)
	at java.base/java.security.SecureClassLoader.defineClass(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.defineClass(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.findClassOnClassPathOrNull(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(Unknown Source)
	at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(Unknown Source)
	at java.base/java.lang.ClassLoader.loadClass(Unknown Source)
	at kotlinx.coroutines.SupervisorKt.SupervisorJob(Supervisor.kt:27)
	at kotlinx.coroutines.SupervisorKt.SupervisorJob$default(Supervisor.kt:27)
	at dev.recorddisc.player.MainKt.main(main.kt:36)
	at dev.recorddisc.player.MainKt.main(main.kt)
Caused by: java.lang.ClassNotFoundException: kotlin.coroutines.CoroutineContext$Element
	at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(Unknown Source)
	at java.base/java.lang.ClassLoader.loadClass(Unknown Source)
	... 36 more

```
* The error means the Kotlin standard library (kotlin-stdlib) isn’t on the classpath when the packaged app runs. kotlinx.coroutines was found, since SupervisorJob started loading, but kotlin.coroutines.CoroutineContext lives in kotlin-stdlib, and that jar is missing. Your code and dependencies compiled fine. The packaging step just didn’t ship the stdlib.