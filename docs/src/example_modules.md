# Examples

This chapter contains some real-world examples of Icejar modules.

## Rename

This module allows registered users to re-name themselves using a chat command.
It demonstrates applying configured values to a module and registering
callbacks for events on the Mumble server.

```java
import MumbleServer.*;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Current;

import static icejar.IceHelper.*;
import static icejar.ConfigHelper.*;

import java.util.Map;
import java.util.EnumMap;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;


// This module allows registered users to rename themselves by sending a
// message which begins with a configurable prefix (default is "!rename ")
// followed by the new name they want.
//
// Optionally, a regular expression can be provided which defines which names
// are permitted.

public class Rename implements icejar.Module {

    // Define the configuration for this module. camelCase field names are
    // automatically translated to snake_case when loading values from the
    // TOML configuration file.
    public record Config(
            String messagePrefix, String regexErrorMessage,
            String nameTakenErrorMessage, String nameRegex) {}

    private Logger logger;

    // Receive logger from icejar. This method will be called immediately after
    // this class is instantiated and before any calls to the `setup()` method.
    @Override
    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    // Setup method, called every time a connection to a mumble server is
    // established for this module.
    @Override
    public void setup(
            Map<String, Object> cfg, MetaPrx meta, ObjectAdapter adapter,
            ServerPrx server) throws Exception
    {
        // Parse a Config record out of the configuration map.
        Config config = parseConfig(cfg, Config.class);

        // Instance a callback which will respond to rename requests.
        RenameCallback renameCallback = new RenameCallback(server, logger, config);

        // Add the callback to the mumble server.
        addServerCallback(server, adapter, renameCallback);
    }
}


class RenameCallback implements icejar.DefaultServerCallback {
    private static final String DEFAULT_MESSAGE_PREFIX =
        "!rename ";
    private static final String DEFAULT_REGEX_ERROR_MESSAGE =
        "<b style=\"color: red;\">That username is not allowed!</b>";
    private static final String DEFAULT_NAME_TAKEN_ERROR_MESSAGE =
        "<b style=\"color: red;\">That username is taken!</b>";

    private String messagePrefix;
    private Pattern namePattern;
    private String regexErrorMessage;
    private String nameTakenErrorMessage;
    private ServerPrx server;
    private Logger logger;

    public RenameCallback(
            ServerPrx server, Logger logger,
            Rename.Config config) throws Exception
    {
        this.server = server;
        this.logger = logger;

        // Apply the values from `config`

        this.messagePrefix = Optional.ofNullable(config.messagePrefix())
            .orElse(DEFAULT_MESSAGE_PREFIX);

        this.regexErrorMessage = Optional.ofNullable(config.regexErrorMessage())
            .orElse(DEFAULT_REGEX_ERROR_MESSAGE);

        this.nameTakenErrorMessage = Optional.ofNullable(config.nameTakenErrorMessage())
            .orElse(DEFAULT_NAME_TAKEN_ERROR_MESSAGE);

        if (config.nameRegex() != null) {
            this.namePattern = Pattern.compile(
                    config.nameRegex(), Pattern.UNICODE_CHARACTER_CLASS);
        }
    }

    // This method is called every time a user sends a text message.
    @Override
    public void userTextMessageThrowsException(
            User state, TextMessage message, Current current)
            throws Exception
    {
        // Return early if the message is not a valid rename request.
        if (!message.text.startsWith(messagePrefix) || state.userid == -1) {
            return;
        }

        String newName = message.text.substring(messagePrefix.length(), message.text.length());

        if (namePattern == null || namePattern.matcher(newName).matches()) {
            // If the name is not blocked by the given name regex...

            // Update the user's name in the registration database. This
            // makes the name change persistent, but will not immediately
            // change the user's visible name.
            Map<UserInfo, String> info = new EnumMap<UserInfo, String>(UserInfo.class) {{
                put(UserInfo.UserName, newName);
            }};

            try {
                server.updateRegistration(state.userid, info);

                // Update the user's state with the new name. This makes the
                // name change immediately visible.
                User newState = server.getState(state.session);
                newState.name = info.get(UserInfo.UserName);
                server.setState(newState);

                // Write a message to the log when a user is renamed, since
                // the mumble server doesn't log this by itself.
                logger.info(
                        "User `" + state.name + "` (ID " + state.userid
                        + ") renamed to `" + newName + "`.");
            } catch (Exception e) {
                server.sendMessage(state.session, nameTakenErrorMessage);
            }
        } else {
            // Send a response to the user if the name was rejected.
            server.sendMessage(state.session, regexErrorMessage);
        }
    }
}
```
