# Step 3: Message Passing

Icejar has a message passing feature to support communication between modules.
To demonstrate this feature in this tutorial, message passing will be used to
send a message from `demo_module` to itself. This is redundant, but will show
how to properly use the message passing API.

First, import the [`MessagePassing`](../module-api/icejar/MessagePassing.html)
utility class:

```java
import static icejar.MessagePassing.*;
```

We're going to make it so that instead of just printing messages to the
standard output, the module sends a text message back with the same contents
like an echo.

Add an instance variable to `Module` so that it can keep track of the
`ServerPrx` object which we'll need to send messages to the server.

Add the following to the `Module` class:

```java
private ServerPrx server;
```

and add the following to the body of its `setup` method:

```java
this.server = server;
```

Now, we can access `server` in any of `Module`'s instance methods (provided
they get called after the call to `setup`).

Add the following method to `Module`:

```java
public void echoTextMessage(TextMessage message) {
    try {
        sendMessageSameDestination(server, message, message.text);
    } catch (Exception e) {
        System.err.println(e);
    }
}
```

The [`TextMessage`](../ice-generated/MumbleServer/TextMessage.html) object
stores not only the contents of the message, but its destination as well. Using
the
[`sendMessageSameDestination`](../module-api/icejar/IceHelper.html#sendMessageSameDestination(MumbleServer.ServerPrx,MumbleServer.TextMessage,java.lang.String))
helper method, we can send the `message.text` String to the same destination as
`message`.

This call might result in an error (if the connection to the Mumble server
died, etc.) so we'll wrap it in a `try ... catch` block.

Right now, the method to respond to messages is in the `Module` class, but
messages are received by the `Callback` class. We will use message passing to
send instances of `TextMessage` from `Callback` to `Module`.

To get access to Icejar's message passing features, we implement the
[`setupMessagePassing`](../module-api/icejar/Module.html#setupMessagePassing(icejar.MessagePassing.Coordinator))
method for `Module`.

Add the following method to the `Module` class:

```java
@Override
public void setupMessagePassing(Coordinator c) {
    Receiver<TextMessage> receiver = c.getReceiver(
        TextMessage.class, this::echoTextMessage);
    sender = c.getSender("demo_module");
}
```

Additionally, add `sender` as an instance variable of `Module` like so:

```java
private Sender<TextMessage> sender;
```

The [`Coordinator`](../module-api/icejar/MessagePassing.Coordinator.html)
object allows the creation of
[`Senders`](../module-api/icejar/MessagePassing.Sender.html)
and
[`Receivers`](../module-api/icejar/MessagePassing.Receiver.html)
for the current module.

`Receivers` implicity receive messages for the current module and are created
by providing the expected type for values sent to the `Receiver`. In this example,
the receiver expects `TextMessage` objects.

Since the message passing API is intended for sending messages _between_
modules instead of within the same module like in this example, `Senders` are
created with the name of the module to which its messages will be sent. In
this example the name of the current module, `"demo_module"`, is used.

The behaviour of a `Receiver` upon receiving a message is defined using its
handler method. The handler method can be set in the call to `getReceiver` as
in this example, or it can be omitted and set later using the `setHandler`
method of the receiver. The handler method is called whenever a message is
received. The handler method must have no return value (`void`) and accept a
single parameter: a value of the type expected by the `Receiver`. In this
example, we can use `this::echoTextMessage` as the handler method since it
meets the criteria.

Right now `Module` can receive `TextMessage` objects and echo their contents
back to the Mumble server, but `Callback` still doesn't have access to a
`Sender`.

To fix this, add the following constructor to `Callback`:

```java
public Callback(Sender<TextMessage> sender) {
    this.sender = sender;
}
```

Additionally, add the `sender` instance variable:

```java
private Sender<TextMessage> sender;
```

Now, the following can be added to the `userTextMessageThrowsException` method
to pass the incoming `TextMessages` to `Module`:

```java
sender.send(message);
```

The print statement can now be removed, since we will be able to see that the
module is working in the Mumble chat log.

Finally, update `Module`'s `setup` method to instantiate `Callback` using its
new constructor:

```java
addServerCallback(server, adapter, new Callback(sender));
```

`setupMessagePassing` is guaranteed to be called _before_ `setup`, so we can
use the `sender` instance variable without worrying about it being `null`.

If you re-compile and package the module and run it with Icejar, you will see
that every time you send a message on the Mumble server, the server repeats the
same message.

If you're wondering why this doesn't result in an infinite loop, it's because
the Mumble server doesn't create `userTextMessage` events for messages sent
using Ice.
