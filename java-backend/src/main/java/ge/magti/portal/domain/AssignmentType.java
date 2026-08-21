package ge.magti.portal.domain;

/**
 * Whether a leadership assignment is the standing one or a stand-in.
 *
 * <p>Only {@link #PRIMARY} is constrained to one per scope (V36's two
 * function-based unique indexes). {@link #ACTING} is deliberately
 * unconstrained: a scope may have several acting leaders at once, and one
 * person may act for several scopes -- plan §2, "ერთი ადამიანი დროებით
 * შეიძლება რამდენიმე ჯგუფს მართავდეს".
 */
public enum AssignmentType {
    PRIMARY,
    ACTING
}
