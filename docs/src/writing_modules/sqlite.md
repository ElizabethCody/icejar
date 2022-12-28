# SQLite Storage

Icejar can create and manage [SQLite](https://sqlite.org) databases for each
module. SQLite support is provided using
[sqlite-jdbc](https://github.com/xerial/sqlite-jdbc).

Modules can get access to a SQLite database connection by implementing the
[`setDatabaseConnection`](../../module-api/icejar/Module.html#setDatabaseConnection(java.sql.Connection))
method.

This method will be called once, immediately after the module is instantiated.
Icejar will close the connection automatically when the module is un-loaded. if
the method is *not* implemented by a module, the database connection will be
immediately closed.

A separate SQLite database is created for each module on each configured server,
i.e. if a single module is enabled for multiple servers, a database will be
created for each server-specific module instance.

The SQLite databases are created in Icejar's data directory (see [CLI args](../cli_args.md))
at the following path: `<data directory>/<server name>/<module name>/db.sqlite`.

Icejar does not implement anything beyond creating SQLite databases/managing
connections. The usage of the database is up to each module's implementation.

Using SQLite is beyond the scope of this documentation.
