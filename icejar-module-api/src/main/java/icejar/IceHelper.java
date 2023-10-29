package icejar;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import com.zeroc.Ice.ObjectAdapter;
import MumbleServer.*;

/**
 * Helper methods for using Ice features.
 *
 * Use <code>import static icejar.IceHelper.*;</code> to make the helper methods
 * easily available to your module.
 */
public final class IceHelper {
    private IceHelper() {}

    /**
     * Helper method to register a server callback with Ice.
     */
    public static ServerCallbackPrx getServerCallback(
            ObjectAdapter adapter, ServerCallback callback)
    {
        return ServerCallbackPrx.uncheckedCast(adapter.addWithUUID(callback));
    }

    /**
     * Helper method to add a callback to a Mumble server.
     * 
     * @param server Interface to the specific virtual server to which the
     * callback will be added.
     * @param adapter Interface to create callback objects.
     * @param callback Object implementing the ServerCallback interface.
     *
     * @return The ServerCallbackPrx object which can be used to un-register
     * the callback.
     */
    public static ServerCallbackPrx addServerCallback(
            ServerPrx server, ObjectAdapter adapter,
            ServerCallback callback) throws Exception
    {
        ServerCallbackPrx cb = getServerCallback(adapter, callback);

        server.addCallback(cb);
        return cb;
    }

    /**
     * Helper method to register a context action callback with Ice.
     */
    public static ServerContextCallbackPrx getServerContextCallback(
            ObjectAdapter adapter, ServerContextCallback callback)
    {
        return ServerContextCallbackPrx.uncheckedCast(
                adapter.addWithUUID(callback));
    }

    /**
     * Helper method to add a context action callback to a Mumble server.
     * 
     * @param server Interface to the specific virtual server to which the
     * callback will be added.
     * @param adapter Interface to create callback objects.
     * @param session The session ID of the user to which the action is added.
     * @param action Unique internal name for the action. This name identifies
     * the action to the given callback
     * @param text External name of the action. This is the text label for the
     * action as displayed in the user's Mumble client.
     * @param callback Object implementing the ServerContextCallback interface.
     *
     * @return The ServerContextCallbackPrx object which can be used to
     * un-register the callback.
     */
    public static ServerContextCallbackPrx addServerContextCallback(
            ServerPrx server, ObjectAdapter adapter,
            int session, String action, String text,
            ServerContextCallback callback, int ctx) throws Exception
    {
        ServerContextCallbackPrx cb = getServerContextCallback(adapter, callback);

        server.addContextCallback(session, action, text, cb, ctx);
        return cb;
    }

    /**
     * Helper method to register an authenticator with Ice.
     */
    public static ServerAuthenticatorPrx getServerAuthenticator(
            ObjectAdapter adapter, ServerAuthenticator authenticator)
    {
        return ServerAuthenticatorPrx.uncheckedCast(
                adapter.addWithUUID(authenticator));
    }

    /** 
     * Helper method to add an authenticator to a Mumble server.
     *
     * @param server Interface to the specific virtual server for which the
     * authenticator will be set.
     * @param adapter Interface to create authenticator objects.
     * @param authenticator Object implementing the ServerAuthenticator
     * interface.
     *
     * @return The ServerAuthenticatorPrx object which was set.
     * */
    public static ServerAuthenticatorPrx setServerAuthenticator(
            ServerPrx server, ObjectAdapter adapter,
            ServerAuthenticator authenticator) throws Exception
    {
        ServerAuthenticatorPrx auth = getServerAuthenticator(adapter, authenticator);

        server.setAuthenticator(auth);
        return auth;
    }

    /**
     * Helper method to register a meta callback with Ice.
     */
    public static MetaCallbackPrx getMetaCallback(
            ObjectAdapter adapter, MetaCallback callback)
    {
        return MetaCallbackPrx.uncheckedCast(
                adapter.addWithUUID(callback));
    }

    /**
     * Helper function to add a meta callback (which is notified about the
     * starting and stopping of virtual servers) to a Mumble server.
     *
     * @param meta Interface to the Mumble server.
     * @param adapter Interface to create callback objects.
     * @param callback Object implementing the MetaCallback interface.
     *
     * @return The MetaCallbackPrx object which can be used to un-register the
     * callback.
     */
    public static MetaCallbackPrx addMetaCallback(
            MetaPrx meta, ObjectAdapter adapter,
            MetaCallback callback) throws Exception
    {
        MetaCallbackPrx cb = getMetaCallback(adapter, callback);

        meta.addCallback(cb);
        return cb;
    }

    /**
     * Helper method to send a new message to the same destination as an
     * existing message.
     * <p>
     * This is useful in the implementation of features which respond to text
     * messages from users.
     *
     * @param server Interface to the specific virtual server to which the
     * text message will be sent.
     * @param message Text message from which the destination for the new
     * message will be copied.
     */
    public static void sendMessageSameDestination(
            ServerPrx server, TextMessage message,
            String messageString) throws Exception
    {
        for (int session: message.sessions) {
            server.sendMessage(session, messageString);
        }
        for (int channel: message.channels) {
            server.sendMessageChannel(channel, false, messageString);
        }
        for (int tree: message.trees) {
            server.sendMessageChannel(tree, true, messageString);
        }
    }

    /**
     * Return the registration IDs of users in the group with the given name
     * in the given channel.
     *
     * @param server The interface to the Mumble server
     * @param channel The ID of the channel against which to check group
     * membership
     * @param groupName The name of group whose members will be returned
     *
     * @return The set of registrations IDs of the members of the given group
     * in the given channel.
     */
    public static Set<Integer> getGroupMembers(
            ServerPrx server, int channel, String groupName) throws Exception
    {
        Set<Integer> groupUserIDs = new HashSet<>();
        for (Group group: server.getACL(channel).groups) {
            if (group.name.equals(groupName)) {
                for (int id: group.members) {
                    groupUserIDs.add(id);
                }

                break;
            }
        }

        return groupUserIDs;
    }

    /**
     * Return the currently connected users in the given channel.
     *
     * @param server The interface to the Mumble server
     * @param channel The ID of the channel whose members will be returned
     *
     * @return A mapping from session IDs to user state objects for the users
     * in the given channel on the given server.
     */
    public static Map<Integer, User> getUsersInChannel(
            ServerPrx server, int channel) throws Exception
    {
        Map<Integer, User> allUsers = server.getUsers();
        allUsers.entrySet().removeIf(e -> e.getValue().channel != channel);
        return allUsers;
    }

    /**
     * Return the currently connected users in the given channel who are also
     * in the group with the given name in that channel.
     *
     * @param server The interface to the Mumble server
     * @param channel The ID of the channel whose members will be returned
     * @param groupName The name of the group whose members will be returned
     *
     * @return A mapping from session IDs to user state objects for the users
     * who are both in the given channel and the given group on the given
     * server.
     */
    public static Map<Integer, User> getUsersInGroup(
            ServerPrx server, int channel, String groupName) throws Exception
    {
        Set<Integer> groupUserIDs = getGroupMembers(server, channel, groupName);

        Map<Integer, User> usersInChannel = getUsersInChannel(server, channel);
        usersInChannel.entrySet().removeIf(e -> !groupUserIDs.contains(e.getValue().userid));

        return usersInChannel;
    }

    /**
     * Return the set of channel IDs currently linked to the channel with the
     * given ID.
     *
     * @param server The interface to the Mumble server
     * @param channel The ID of the channel whose links will be returned
     *
     * @return A set of channel IDs that are linked to the channel with the
     * given ID.
     */
    public static Set<Integer> getLinkedChannels(
            ServerPrx server, int channel) throws Exception
    {
        int[] channels = { channel };
        Set<Integer> linkedChannels = new HashSet<>();
        recurseLinkedChannels(server, channels, linkedChannels);
        return Collections.unmodifiableSet(linkedChannels);
    }

    private static void recurseLinkedChannels(
            ServerPrx server, int[] channels, Set<Integer> linkedChannels)
            throws Exception
    {
        for (int channel: channels) {
            if (!linkedChannels.contains(channel)) {
                linkedChannels.add(channel);
                recurseLinkedChannels(
                        server, server.getChannelState(channel).links,
                        linkedChannels);
            }
        }
    }
}
