# Building

To build Icejar from source, you will need the following:

* [Gradle](https://gradle.org).
* *At least* version 17 of the JDK.
* [ZeroC Ice development tools for Java](https://zeroc.com/downloads/ice/3.7/java).
 If possible, you should install these from your distribution's package manager
 rather than from ZeroC's site. You can check package availability
 [here](https://repology.org/project/zeroc-ice/packages).

If you have the dependencies listed above, run `gradle build` in Icejar's
root directory to build the project.

Within the project's directory, the main program is located at
`icejar-client/build/MumbleIce.jar`.

Additionally, a JAR file containing the classes and interfaces required to
write modules for Icejar is located at
`icejar-module-api/build/MumbleIceModuleAPI.jar`.
