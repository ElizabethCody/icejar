# Complete Source Code

Here is the complete source code for `Module.java` after completing all the
steps:

```java
import MumbleServer.*;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Current;

import static icejar.IceHelper.*;
import static icejar.MessagePassing.*;
import static icejar.ConfigHelper.*;

import java.util.Map;
import java.util.logging.Logger;


public class Module implements icejar.Module {

    public static record Config(String beforeMessage, String afterMessage) {}

    private ServerPrx server;
    private Sender<TextMessage> sender;
    private Config config;
    private Logger logger;

    @Override
    public void setupMessagePassing(Coordinator c) {
        Receiver<TextMessage> receiver = c.getReceiver(
            TextMessage.class, this::echoTextMessage);
        sender = c.getSender("demo_module");
    }

    public void echoTextMessage(TextMessage message) {
        try {
            String response = 
                config.beforeMessage()
                + ' ' + message.text + ' '
                + config.afterMessage();

            sendMessageSameDestination(server, message, response);
        } catch (Exception e) {
            System.err.println(e);
        }
    }

    @Override
    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void setup(
            Map<String, Object> cfg, MetaPrx meta,
            ObjectAdapter adapter, ServerPrx server)
            throws Exception
    {
        logger.info("I'm alive!");

        this.server = server;
        config = parseConfig(cfg, Config.class);

        addServerCallback(server, adapter, new Callback(sender));
    }
}


class Callback implements icejar.DefaultServerCallback {

    private Sender<TextMessage> sender;

    public Callback(Sender<TextMessage> sender) {
        this.sender = sender;
    }

    @Override
    public void userTextMessageThrowsException(
            User state, TextMessage message, Current current)
            throws Exception
    {
        sender.send(message);
    }
}
```
