package ge.magti.portal.content;

/** The (itemType, itemId) pair keying a polymorphic content reference. */
public record ItemKey(String itemType, Long itemId) {
}
