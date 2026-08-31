package ge.magti.portal.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@code docs/ROLLOUT_ROLLBACK_KA.md} from promising a lever that does
 * not exist.
 *
 * <p>A rollout switch is only a rollback lever if something reads it. Two of
 * the three do not: {@code ROLLOUT_LEADERSHIP_SCOPE} and {@code
 * ROLLOUT_COMPLIANCE_ELIGIBILITY} are bound, logged at startup, set to
 * {@code "true"} in two compose files -- and never consulted. Flipping either
 * one during an incident changes nothing, which is the worst possible
 * behaviour for a control somebody reaches for under pressure.
 *
 * <p>Nothing in a Java build notices that, because an unread getter is
 * perfectly valid code. So the current state is asserted here instead, in
 * both directions:
 *
 * <ul>
 *   <li>a switch this test lists as UNWIRED must stay unread -- and when
 *       somebody wires it, this test fails and asks them to correct the
 *       document in the same commit;
 *   <li>a switch listed as WIRED must stay read -- so the working lever
 *       cannot quietly stop working.
 * </ul>
 *
 * <p>Reads source text rather than reflection: "is this getter called
 * anywhere in main" is a property of the source, and reflection cannot see
 * a call site at all.
 */
class RolloutSwitchWiringTest {

	private static final Path MAIN = Path.of("src/main/java");
	private static final Path ROLLOUT_DOC_RELATIVE = Path.of("../docs/ROLLOUT_ROLLBACK_KA.md");

	/**
	 * The switches, by the getter a call site would use, and whether anything
	 * in main is expected to call it.
	 *
	 * <p>Changing a value here is the point: it is a two-line edit that says
	 * "this switch now does something", and the assertions below make it
	 * impossible to do the wiring without making that statement.
	 */
	private static final Map<String, Boolean> WIRED = new LinkedHashMap<>(Map.of(
			"isLeadershipScopeEnabled", false,
			"isComplianceEligibilityEnabled", false,
			"isFileEntitlementEnabled", true));

	private static List<Path> mainSources() throws IOException {
		assertTrue(Files.isDirectory(MAIN), "expected to run from the java-backend module; " + MAIN);
		try (Stream<Path> files = Files.walk(MAIN)) {
			return files.filter(path -> path.toString().endsWith(".java"))
					// PortalProperties declares the getters; a declaration is
					// not a call site.
					.filter(path -> !path.endsWith("PortalProperties.java"))
					.toList();
		}
	}

	private static List<String> callSites(String getter) throws IOException {
		List<String> found = new ArrayList<>();
		for (Path source : mainSources()) {
			String text = Files.readString(source);
			for (String line : text.split("\n", -1)) {
				if (line.contains(getter + "()")) {
					found.add(MAIN.relativize(source) + ": " + line.strip());
				}
			}
		}
		return found;
	}

	@Test
	void aSwitchRecordedAsWiredIsStillRead() throws IOException {
		for (Map.Entry<String, Boolean> entry : WIRED.entrySet()) {
			if (!entry.getValue()) {
				continue;
			}
			assertTrue(!callSites(entry.getKey()).isEmpty(),
					entry.getKey() + " is documented as a working rollback lever in "
							+ "docs/ROLLOUT_ROLLBACK_KA.md, and nothing in main calls it any more. "
							+ "Either restore the call site or correct the document.");
		}
	}

	@Test
	void aSwitchRecordedAsUnwiredIsStillUnread() throws IOException {
		for (Map.Entry<String, Boolean> entry : WIRED.entrySet()) {
			if (entry.getValue()) {
				continue;
			}
			List<String> sites = callSites(entry.getKey());
			assertEquals(List.of(), sites,
					entry.getKey() + " now HAS a call site, so the switch does something. That is good news, "
							+ "and it makes docs/ROLLOUT_ROLLBACK_KA.md wrong: the document currently says this "
							+ "flag is read by nothing and that the rollback procedure does not work for it. "
							+ "Update the document and flip this entry to true, in the same commit.");
		}
	}

	/**
	 * The document and this test have to agree about which is which, or the
	 * guard above guards nothing.
	 */
	@Test
	void theDocumentStillSaysWhichSwitchesAreDead() throws IOException {
		assertTrue(Files.exists(ROLLOUT_DOC_RELATIVE),
				"docs/ROLLOUT_ROLLBACK_KA.md not found at " + ROLLOUT_DOC_RELATIVE);
		String doc = Files.readString(ROLLOUT_DOC_RELATIVE);

		assertTrue(doc.contains("RolloutSwitchWiringTest"),
				"the rollout document no longer points at this test, so a reader has no way to know the "
						+ "'nothing reads it' claim is checked rather than remembered");
	}
}
