package com.azentio.bulkhead.screening;

/** Declared in order of severity, so the stricter of two decisions is the one with the higher ordinal. */
public enum Decision {
    APPROVE, REVIEW, DECLINE;

    public Decision atLeast(Decision floor) {
        return floor.ordinal() > ordinal() ? floor : this;
    }

    public boolean raisesAlert() {
        return this != APPROVE;
    }
}
