# Modes

*Note: This documentation is alpha and is subject to change. It also
 may not reflect the current state of the code base.*

The operation of a Lime SMTP server is determined by its modes. They
allow a server to do work on a network connection within the context
of an SMTP session. A server may only have one mode active at a time,
however it may switch modes as the session progresses in order to
handle different situations.

For example, a server will begin in connect mode and will generally
write a greeting reply to the client. It will then instruct the server
to switch to command mode so that it may begin to process commands. At
a later point, when the server is ready to handle mail data, it will
switch to data mode so that it can read it in properly.

## API

A mode is a function that accepts a session map, a configuration map,
a socket, and a reader and writer that is backed by the socket. The
return value of a mode is the, potentially modified, session map.

### Contract

Within the context of a mode, the configuration map is immutable. The
reader and writer are mutable, but are guaranteed to be backed by the
socket. A mode should not assume that either of these three network
objects will be usable once the mode returns. One reason for this is
due to the STARTTLS extension, which is required to replace the socket
with a secure one for the rest of the session.

Since a mode is supposed to return a session map, it is free to alter
the session in order to instruct the server what to do next. Most
importantly, it tells the server what mode it needs to be in next.
This is done by associating the keyword for the mode to the `:mode`
key in the map. If the mode isn't set on the returned map, then the
previous mode is invoked again. If the set mode isn't recognized, then
the server will respond with a fatal error on the next opportunity to
write a reply.

### Session Loop

Modes are dispatched within a session loop. Once a client has
connected to the server, the session loop will begin. It will continue
looping until the socket is no longer open. Each iteration of the loop
will invoke a mode based on the current state of the session, and more
specifically, the value set to `:mode` in the session map.

## Provided Modes

The following modes are provided as part of the core SMTP server.

### Connect

As soon as a connection is established with the server, it will need
to send a reply. Connect mode wont read anything and will reply with
its status. Unless there is some reason the server cannot comply, or
is configured to reject the connection, it will return a 220 reply
which indicates that the server is ready to proceed. With a successful
reply, the server will switch to command mode.

### Command

The majority of the time the session will be in command mode. This
means the server will read a command from the network and delegate it
to the appropriate command handler function. These functions are
specified in the configuration under the `:commands` key.

A command function accepts a session map and a string that represents
the command's parameters. If no parameters are given with the command,
then the value of the argument will be nil. As with modes, a command
function returns a session map. In addition to being able to set the
mode, a command function must set a reply, which is a map associated
to `:reply` that contains the status code associated to `:code`. The
reply may also have a value for `:text` if it has a single line of
text, or `:lines` if it is a multi-line reply.
 
By default the server comes with all the commands required for SMTP to
operate. Extensions are allowed to add or replace commands in order to
fulfill their requirements. However, they must be careful to not
interfere with the requirements of SMTP or other extensions. Other
extensions are free to use the command functions as long as they rely
on their presence in the configuration map.

If an extension requires a command function to change in a small way,
it would be ridiculous for it to be required to replace a function
with only a small modification. This way of modification will also
break things if multiple extensions need to make their own slight
changes. There needs to be a way to provide these fine grained
modifications without completely replacing the function. How this will
work depends on what kind of changes extensions require.

### Data

This mode is specifically for reading the mail data after the reply to
the DATA command is sent. Once the message has been received, the mode
will be switched back to command mode.

### Quit

When the QUIT command is handled, it will set the mode to quit mode.
This is so that the final reply can be written to the client and the
network connection can be closed.
