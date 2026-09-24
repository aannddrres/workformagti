package ge.magti.portal.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentSecretRecipeTest {
    private static final Set<String> REQUIRED = Set.of("SECRET_KEY", "ORACLE_DB_PASSWORD", "OAUTH_SECRET");
    private static final Pattern KEY = Pattern.compile("--from-(?:literal|file)=([A-Z_]+)=");

    @Test
    void everySecretCreationRecipeSuppliesAllThreeKeysWithoutLiteralValues() throws Exception {
        for (String path : List.of("k8s/README_KA.md", "k8s/20-secret.EXAMPLE.yaml")) {
            List<String> recipes = recipes(Files.readString(RepoRoot.path(path)));
            assertFalse(recipes.isEmpty(), path + " must contain a complete secret recipe");
            for (String recipe : recipes) {
                assertEquals(REQUIRED, keys(recipe), path + " secret recipe keys");
                assertFalse(recipe.contains("--from-literal"), path + " must not put values in shell arguments");
                assertTrue(recipe.contains("portal-backend-secrets"), path + " secret name");
            }
        }
    }

    @Test
    void exampleDeclaresTheSameKeysButIsNeverAppliedByKustomize() throws Exception {
        String yaml = Files.readString(RepoRoot.path("k8s/20-secret.EXAMPLE.yaml"));
        Set<String> keys = new HashSet<>();
        Pattern.compile("(?m)^  ([A-Z_]+):").matcher(yaml).results().forEach(match -> keys.add(match.group(1)));
        assertEquals(REQUIRED, keys);
        assertFalse(Files.readString(RepoRoot.path("k8s/kustomization.yaml")).lines()
                .map(line -> line.split("#", 2)[0])
                .anyMatch(line -> line.contains("20-secret.EXAMPLE.yaml")));
    }

    @Test
    void parserFindsAnIncompleteMultilineCommentedRecipe() {
        String text = "# kubectl create secret generic portal-backend-secrets \\\n"
                + "#   --from-file=SECRET_KEY=/fixture/key\n";
        assertEquals(1, recipes(text).size());
        assertEquals(Set.of("SECRET_KEY"), keys(recipes(text).getFirst()));
        assertFalse(REQUIRED.equals(keys(recipes(text).getFirst())));
    }

    private static Set<String> keys(String recipe) {
        Set<String> keys = new HashSet<>();
        KEY.matcher(recipe).results().forEach(match -> keys.add(match.group(1)));
        return keys;
    }

    private static List<String> recipes(String text) {
        String joined = text.replaceAll("(?m)^# ?", "").replaceAll("\\\\\\r?\\n", " ");
        List<String> recipes = new ArrayList<>();
        joined.lines().filter(line -> line.contains("kubectl") && line.contains("create secret generic"))
                .forEach(recipes::add);
        return recipes;
    }
}
