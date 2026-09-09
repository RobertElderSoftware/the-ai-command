package org.res.ai;

import java.util.Objects;

/**
 * Typed key for one value in an input-space partition test scenario.
 *
 * <p>Keys use identity semantics, allowing independent test families to use
 * the same descriptive name without accidentally sharing state.</p>
 */
public final class InputSpacePartitionStateKey<T> {
    private final String name;
    private final Class<T> valueType;

    public InputSpacePartitionStateKey(String name, Class<T> valueType) {
        String nonNullName = Objects.requireNonNull(name, "name");
        if (nonNullName.isBlank()) {
            throw new IllegalArgumentException("State key name must not be blank");
        }
        this.name = nonNullName;
        this.valueType = Objects.requireNonNull(valueType, "valueType");
    }

    public static <T> InputSpacePartitionStateKey<T> of(
            String name, Class<T> valueType) {
        return new InputSpacePartitionStateKey<>(name, valueType);
    }

    public String name() {
        return name;
    }

    public Class<T> valueType() {
        return valueType;
    }

    T validateValue(Object value) {
        if (!valueType.isInstance(value)) {
            throw new IllegalArgumentException(
                    "Value for state key '" + name + "' must be a "
                            + valueType.getName());
        }
        return valueType.cast(value);
    }

    @Override
    public String toString() {
        return name;
    }
}
