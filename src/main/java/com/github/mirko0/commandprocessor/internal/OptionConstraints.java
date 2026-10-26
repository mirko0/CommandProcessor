package com.github.mirko0.commandprocessor.internal;

import com.github.mirko0.commandprocessor.annotations.CommandOption;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

/**
 * Applies {@link CommandOption#minValue()}, {@link CommandOption#maxValue()},
 * {@link CommandOption#minLength()} and {@link CommandOption#maxLength()}.
 *
 * <p>Unlike a long description or a 26th choice, an unusable bound is not repaired. A handler that
 * declares one relies on Discord never delivering anything outside it, so dropping it would quietly
 * hand the handler input it was written not to receive. It throws instead, which skips the one
 * command and lets the rest register.
 *
 * <p><strong>Internal.</strong> Not part of the public API and may change in any release.
 *
 * @since 0.5.0
 */
final class OptionConstraints {

    private static final int UNSET_LENGTH = -1;

    private OptionConstraints() {
    }

    /**
     * @throws IllegalArgumentException naming the option and class when a bound is unusable
     */
    static void apply(OptionData option, CommandOption anno, String owner, String origin) {
        String where = "Option '" + option.getName() + "' of '/" + owner + "' (" + origin + ")";
        applyRange(option, anno, where);
        applyLength(option, anno, where);
    }

    private static void applyRange(OptionData option, CommandOption anno, String where) {
        double min = anno.minValue();
        double max = anno.maxValue();
        if (Double.isNaN(min) && Double.isNaN(max)) return;

        OptionType type = option.getType();
        if (type != OptionType.INTEGER && type != OptionType.NUMBER) {
            throw new IllegalArgumentException(where + " declares minValue/maxValue but is of type " + type
                    + ". Value ranges only apply to INTEGER and NUMBER options.");
        }
        checkBound(min, "minValue", type, where);
        checkBound(max, "maxValue", type, where);
        if (!Double.isNaN(min) && !Double.isNaN(max) && min > max) {
            throw new IllegalArgumentException(where + " declares minValue " + min
                    + " greater than maxValue " + max + ".");
        }

        if (!Double.isNaN(min)) {
            if (type == OptionType.INTEGER) option.setMinValue((long) min);
            else option.setMinValue(min);
        }
        if (!Double.isNaN(max)) {
            if (type == OptionType.INTEGER) option.setMaxValue((long) max);
            else option.setMaxValue(max);
        }
    }

    private static void checkBound(double value, String member, OptionType type, String where) {
        if (Double.isNaN(value)) return;
        if (Double.isInfinite(value)
                || value < OptionData.MIN_NEGATIVE_NUMBER || value > OptionData.MAX_POSITIVE_NUMBER) {
            throw new IllegalArgumentException(where + " declares " + member + " " + value
                    + ", outside Discord's range of " + (long) OptionData.MIN_NEGATIVE_NUMBER
                    + " to " + (long) OptionData.MAX_POSITIVE_NUMBER + ".");
        }
        if (type == OptionType.INTEGER && value != Math.rint(value)) {
            throw new IllegalArgumentException(where + " is an INTEGER option but declares " + member
                    + " " + value + ", which is not a whole number.");
        }
    }

    private static void applyLength(OptionData option, CommandOption anno, String where) {
        int min = anno.minLength();
        int max = anno.maxLength();
        if (min == UNSET_LENGTH && max == UNSET_LENGTH) return;

        if (option.getType() != OptionType.STRING) {
            throw new IllegalArgumentException(where + " declares minLength/maxLength but is of type "
                    + option.getType() + ". Length limits only apply to STRING options.");
        }
        if (min != UNSET_LENGTH && (min < 0 || min > OptionData.MAX_STRING_OPTION_LENGTH)) {
            throw new IllegalArgumentException(where + " declares minLength " + min
                    + "; it must be between 0 and " + OptionData.MAX_STRING_OPTION_LENGTH + ".");
        }
        if (max != UNSET_LENGTH && (max < 1 || max > OptionData.MAX_STRING_OPTION_LENGTH)) {
            throw new IllegalArgumentException(where + " declares maxLength " + max
                    + "; it must be between 1 and " + OptionData.MAX_STRING_OPTION_LENGTH + ".");
        }
        if (min != UNSET_LENGTH && max != UNSET_LENGTH && min > max) {
            throw new IllegalArgumentException(where + " declares minLength " + min
                    + " greater than maxLength " + max + ".");
        }

        // 0 is Discord's default, and JDA refuses to set it explicitly.
        if (min > 0) option.setMinLength(min);
        if (max != UNSET_LENGTH) option.setMaxLength(max);
    }
}
