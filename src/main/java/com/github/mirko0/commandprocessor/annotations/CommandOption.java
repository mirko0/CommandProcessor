package com.github.mirko0.commandprocessor.annotations;

import net.dv8tion.jda.api.interactions.commands.OptionType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CommandOption {
    OptionType type();
    String name();
    LocalizedEntry[] localizedNames() default {};
    String description() default "";
    LocalizedEntry[] localizedDescriptions() default {};
    boolean required() default false;
    boolean autoComplete() default false;

    /**
     * Fixed choices, each {@code "<value>:<label>"}, or just {@code "<value>"} to use the value as
     * its label.
     *
     * <p>On an {@code INTEGER} or {@code NUMBER} option the value must parse as one; a value that
     * does not makes the command fail registration, because Discord rejects a text value there.
     *
     * @return the choices; empty for free input
     */
    String[] choices() default {};

    /**
     * The smallest value Discord will let the user enter. {@code INTEGER} and {@code NUMBER} only.
     *
     * <p>NaN — the default — sets no minimum. On an {@code INTEGER} option the value must be whole.
     * Discord enforces it client-side, so the handler never receives anything lower.
     *
     * <p>A minimum on any other type, one above {@link #maxValue()}, or one outside Discord's range
     * of ±(2<sup>53</sup> − 1) fails registration of the command with a message naming the option.
     *
     * @return the inclusive minimum, or NaN for none
     * @since  0.5.0
     */
    double minValue() default Double.NaN;

    /**
     * The largest value Discord will let the user enter. {@code INTEGER} and {@code NUMBER} only.
     *
     * <p>Validated the same way as {@link #minValue()}.
     *
     * @return the inclusive maximum, or NaN for none
     * @since  0.5.0
     */
    double maxValue() default Double.NaN;

    /**
     * The fewest characters Discord will accept. {@code STRING} only.
     *
     * <p>-1 — the default — sets no minimum, and so does 0, which is Discord's own default. Any
     * other value outside 0–6000, one above {@link #maxLength()}, or a length on any other type
     * fails registration of the command with a message naming the option.
     *
     * @return the inclusive minimum length, or -1 for none
     * @since  0.5.0
     */
    int minLength() default -1;

    /**
     * The most characters Discord will accept. {@code STRING} only.
     *
     * <p>-1 — the default — sets no maximum. Otherwise it must be between 1 and 6000.
     *
     * @return the inclusive maximum length, or -1 for none
     * @since  0.5.0
     */
    int maxLength() default -1;
}
