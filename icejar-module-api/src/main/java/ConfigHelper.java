package icejar;

import java.util.Arrays;
import java.util.Map;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;


/** Helper methods for loading values from a module's configuration */
public final class ConfigHelper {
    private ConfigHelper() {}

    private static String camelCaseToSnakeCase(String camelCase) {
        StringBuilder builder = new StringBuilder();

        for (char c: camelCase.toCharArray()) {
            if (Character.isUpperCase(c)) {
                builder.append('_');
                builder.append(Character.toLowerCase(c));
            } else {
                builder.append(c);
            }
        }

        return builder.toString();
    }

    /** Parse a record of the given Class from the given Map.
     * Record member names will be translated from camelCase to snake_case
     * for the purpose of reading their values from the map.
     */
    public static <R extends Record> R parseConfig(
            Map<String, Object> config, Class<R> cls) throws Exception
    {
        RecordComponent[] components = cls.getRecordComponents();

        Class<?>[] paramTypes =
            Arrays.stream(components)
            .map(RecordComponent::getType)
            .toArray(Class<?>[]::new);
        Constructor<R> constructor = cls.getDeclaredConstructor(paramTypes);

        Object[] args = new Object[paramTypes.length];

        for (int i = 0; i < args.length; i++) {
            try {
                String configName = camelCaseToSnakeCase(
                        components[i].getName());
                args[i] = paramTypes[i].cast(config.get(configName));
            } catch (Exception e) {
                args[i] = null;
            }
        }

        return constructor.newInstance(args);
    }
}
