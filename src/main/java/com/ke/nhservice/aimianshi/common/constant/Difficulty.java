package com.ke.nhservice.aimianshi.common.constant;

/**
 * 难度三档。档位是有序的，shift() 靠 ordinal 升降并夹紧边界。
 */
public enum Difficulty {

    EASY("简单"),
    MEDIUM("中等"),
    HARD("困难");

    private final String label;

    Difficulty(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 从中文标签解析，认不出来就当「中等」 */
    public static Difficulty fromLabel(String label) {
        if (label == null || label.isBlank()) {
            return MEDIUM;
        }
        String v = label.trim();
        for (Difficulty d : values()) {
            if (d.label.equals(v)) {
                return d;
            }
        }
        try {
            return valueOf(v.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MEDIUM;
        }
    }

    /**
     * 升降档。困难再升仍是困难，简单再降仍是简单——不会越界。
     *
     * @param delta +1 升档 / -1 降档 / 0 不变
     */
    public static Difficulty shift(Difficulty current, int delta) {
        Difficulty cur = current == null ? MEDIUM : current;
        int index = cur.ordinal() + delta;
        return values()[Math.max(0, Math.min(values().length - 1, index))];
    }
}