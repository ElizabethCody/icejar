package icejar;

import java.util.Arrays;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Constructor;


final class ClassConverter {

    private ClassConverter() {}

    @SuppressWarnings("unchecked")
    public static <C> C convert(Object obj, Class<C> cls) throws Exception {
        if (obj == null) {
            // return early if null, since no further conversion is required.
            return null;
        }

        Class<?> objCls = obj.getClass();

        if (objCls.isRecord() && cls.isRecord()) {
            // Special handling for equivalent record classes, i.e. records
            // which might be different classes, but whose members have the
            // same types so we can convert easily.

            RecordComponent[] objComponents = objCls.getRecordComponents();
            RecordComponent[] components = cls.getRecordComponents();
            Object[] args = new Object[components.length];

            Class<?>[] paramTypes =
                Arrays.stream(components)
                .map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
            Constructor<C> constructor = cls.getDeclaredConstructor(paramTypes);

            for (int i = 0; i < args.length; i++) {
                args[i] = convert(
                        objComponents[i].getAccessor().invoke(obj),
                        paramTypes[i]);
            }

            return constructor.newInstance(args);
        } else if (objCls.isArray() && cls.isArray()) {
            // Special handling for arrays

            Object[] objArr = (Object[]) obj;
            Object[] arr = new Object[objArr.length];
            Class<?> componentType = objCls.componentType();
            Class<? extends Object[]> arrCls = (Class<? extends Object[]>) cls;

            for (int i = 0; i < objArr.length; i++) {
                arr[i] = convert(objArr[i], componentType);
            }

            return cls.cast(Arrays.copyOf(arr, arr.length, arrCls));
        } else if (objCls.isEnum() && cls.isEnum()) {
            // Special handling for enums

            Enum<?> objEnum = (Enum) obj;
            Class<? extends Enum> enumCls = (Class<? extends Enum>) cls;

            return cls.cast(Enum.valueOf(enumCls, objEnum.name()));
        } else {
            return cls.cast(obj);
        }
    }
}
