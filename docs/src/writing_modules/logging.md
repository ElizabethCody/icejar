# Step 5: Logging

So far, we've used print statements everywhere in our module. This would
quickly get out of hand if you were running multiple modules which all print
their own messages.

To solve this, Icejar provides a `Logger` to which messages can be written
instead of printing them directly from the module.

First, import the Java `Logger` class:

```
import java.util.logging.Logger;
```

Next, implement the
[`setLogger`](../../module-api/icejar/Module.html#setLogger(java.util.logging.Logger))
method for `Module`:

```java
@Override
public void setLogger(Logger logger) {
    this.logger = logger;
}
```

Additionally, add the `logger` instance variable:

```java
private Logger logger;
```

Now, instead of using a print statement in `Module`'s `setup` method, we can
use the following:

```java
logger.info("I'm alive!");
```

This will automatically timestamp the message and show the server _and_ module
from which the message originates.

