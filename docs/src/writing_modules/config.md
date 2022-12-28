# Step 4: Custom Configuration

Often, you'll want to make some values in your module modifiable by its users
without having to edit the source code and re-compile.

First, import the [`ConfigHelper`](../../module-api/icejar/ConfigHelper.html)
utility class:

```java
import static icejar.ConfigHelper.*;
```

We're going to make it so that instead of just repeating messages, the module
adds some text before and after each message.

To use the `ConfigHelper`, we must first define the data to parse. We will do
this with a `Record` class. Add the following `Record` class as a nested class
within the `Module` class:

```java
public static record Config(String beforeMessage, String afterMessage) {}
```

We can now add a call to the
[`parseConfig`](../../module-api/icejar/ConfigHelper.html#parseConfig(java.util.Map,java.lang.Class))
helper method to the body of the `setup` method in `Module`:

```java
config = parseConfig(cfg, Config.class);
```

Additionally, we'll need to add a `config` instance variable to `Module`:

```java
private Config config;
```

Now, we can modify the `echoTextMessage` method to use `config` like so:

```java
String response = 
    config.beforeMessage()
    + ' ' + message.text + ' '
    + config.afterMessage();

sendMessageSameDestination(server, message, response);
```

If `beforeMessage` and `afterMessage` are undefined, response will just be
`message.text` with `null` before and after. This could be handled, but we
won't for brevity's sake.

To configure the values of `beforeMessage` and `afterMessage`, the following
would be added to the server configuration file
(see [usage](usage.md#using-modules)):

```toml
[demo_module]
before_message = "something"
after_message = "something else!"
```

With these settings, the module would respond to the message containing "foo"
with "something foo something else!"

Since TOML values are written in `pothole_case` while Java members use
`camelCase`, the `parseConfig` method automatically converts from the former
to the latter, i.e. `before_message` sets the `beforeMessage` member of
`Config` while `after_message` sets the `afterMessage` member.

