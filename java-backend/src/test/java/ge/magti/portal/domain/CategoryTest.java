package ge.magti.portal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryTest {

    @Test
    void newInstanceMatchesModelsPyColumnDefaults() {
        Category category = new Category();

        assertTrue(category.isActive());
        assertNull(category.getParentId());
    }
}
