# Writing Modules

This chapter is a tutorial which covers the basics of writing modules for
Icejar. For real-world examples of modules which add real functionality rather
than being contrived demonstrations of Icejar's features, see the
[example modules](example_modules.md).

## Setup

You can use a proper build system to write modules, but in this tutorial we
will just be using the Java compiler directly.

Create a directory called `demo_module` with the following contents:

* A new file called `Module.java`: this will be the source for the module
* `MumbleIceModuleAPI.jar` (see [Building](building.md))
