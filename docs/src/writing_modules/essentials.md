# Step 1: Essentials

Add the following contents to `Module.java`:

```java
import MumbleServer.*;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Current;

import java.util.Map;


public class Module implements icejar.Module {

    @Override
    public void setup(
            Map<String, Object> cfg, MetaPrx meta,
            ObjectAdapter adapter, ServerPrx server)
            throws Exception
    {
        System.out.println("I'm alive!");
    }
}
```

You can now run the following from the `demo_module` directory to compile
the module:

```shell
$ javac -classpath MumbleIceModuleAPI.jar Module.java
```

This is the bare minimum for an Icejar module:

* It implements the `Module` interface from the `icejar` package.
* It contains an implementation of the `setup` method.
* `import MumbleServer.*` provides the auto-generated types used to interface
  with a Mumble server over Ice (such as `MetaPrx` and `ServerPrx`).
* [`ObjectAdapter`](https://doc.zeroc.com/api/ice/3.7/java/com/zeroc/Ice/ObjectAdapter.html)
  allows new objects to be registered with Ice. This is needed to add callbacks
  to the Mumble server.
* [`Current`](https://doc.zeroc.com/api/ice/3.7/java/com/zeroc/Ice/Current.html)
  provides access to the state of the current Ice connection.

Modules must provide a constructor with no arguments, i.e. `new Module()` must
be a valid way to create an instance. In this example we don't specify any
constructor so the default constructor is automatically provided, but this is
worth keeping in mind if you write a module which defines its constructor.

The arguments to the `setup` method are the following:
* `cfg` - A mapping from configuration option names to their values. The
  configuration for each module is read from the server and passed in to the
  relevant module by Icejar.
* [`meta`](../ice-generated/MumbleServer/MetaPrx.html) - Provides access to global Mumble server functionality, such as
  getting the version number of the Mumble server, getting its current uptime,
  or reading values from the Mumble server INI configuration file. It also
  facilitates control of the Mumble server's
  [virtual servers feature](https://wiki.mumble.info/wiki/FAQ/English#Can_I_run_multiple_servers_on_one_host.3F).
* [`adapter`](https://doc.zeroc.com/api/ice/3.7/java/com/zeroc/Ice/ObjectAdapter.html) - This is provided to allow creating new objects which communicate
  with the Mumble server over Ice. It is mainly needed to create callbacks.
* [`server`](../ice-generated/MumbleServer/ServerPrx.html) - Provides access to the specific virtual server for which the
  module was instanced. This can be used to send messages to the server,
  access/modify user data, modify channels, etc.

The `setup` method is called _each_ time a connection to a Mumble server is
established for the given module and modules should _not_ assume that `setup`
is only called for newly constructed instances.

More specifically: if a module is instatiated and connects to a Mumble server,
`setup` will be called. Importantly, if the Mumble server stops or the
connection is othewise interrupted and then the Mumble server becomes available
again, `setup` will called with the new connection details for the _same_
instance of the module.

