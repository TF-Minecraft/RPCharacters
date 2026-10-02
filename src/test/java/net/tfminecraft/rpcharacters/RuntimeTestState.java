package net.tfminecraft.rpcharacters;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/** Restores static configuration references and collection contents changed by a test. */
public final class RuntimeTestState implements AutoCloseable {
    private record Saved(Field field, Object reference, Object contents) {}
    private final List<Saved> saved = new ArrayList<>();
    private final Locale locale = Locale.getDefault();

    public RuntimeTestState(Class<?>... additionalOwners) {
        List<Class<?>> owners = new ArrayList<>();
        owners.add(Cache.class);
        for (Class<?> owner : additionalOwners) if (!owners.contains(owner)) owners.add(owner);
        try {
            for (Class<?> owner : owners) {
                for (Field field : owner.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                    field.setAccessible(true);
                    Object value = field.get(null);
                    Object contents = value instanceof Map<?, ?> map ? new LinkedHashMap<>(map)
                        : value instanceof List<?> list ? new ArrayList<>(list)
                        : value instanceof Set<?> set ? new LinkedHashSet<>(set) : null;
                    if (!Modifier.isFinal(field.getModifiers()) || contents != null) {
                        saved.add(new Saved(field, value, contents));
                    }
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not snapshot test configuration", failure);
        }
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void close() {
        try {
            for (int i = saved.size() - 1; i >= 0; i--) {
                Saved value = saved.get(i);
                if (!Modifier.isFinal(value.field().getModifiers())) value.field().set(null, value.reference());
                if (value.contents() != null && !value.contents().equals(value.reference())) {
                    if (value.reference() instanceof Map map) {
                        map.clear(); map.putAll((Map) value.contents());
                    } else if (value.reference() instanceof List list) {
                        list.clear(); list.addAll((List) value.contents());
                    } else if (value.reference() instanceof Set set) {
                        set.clear(); set.addAll((Set) value.contents());
                    }
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not restore test configuration", failure);
        } finally {
            Locale.setDefault(locale);
        }
    }
}
