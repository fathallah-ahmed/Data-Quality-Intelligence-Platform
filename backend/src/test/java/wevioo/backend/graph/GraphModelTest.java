package wevioo.backend.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wevioo.backend.graph.GraphModel.*;
import wevioo.backend.graph.GraphStore.NodeRow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GraphModelTest {
    private final GraphModeler modeler = new GraphModeler();
    private final FakeStore store = new FakeStore();
    private final GraphBuilder builder = new GraphBuilder(store);

    @TempDir
    Path dir;

    /** Remembers what would have been written to Neo4j. */
    static final class FakeStore implements GraphStore {
        final Map<String, List<NodeRow>> nodes = new LinkedHashMap<>();
        final Map<String, List<String[]>> relationships = new LinkedHashMap<>();
        String cleared;

        @Override public void ping() {}
        @Override public void clear(String scope) { cleared = scope; }
        @Override public void clearPrefix(String prefix) {}
        @Override public void writeNodes(String scope, String label, List<NodeRow> rows) { nodes.put(label, rows); }
        @Override public void writeRelationships(String scope, String type, String from, String to, List<String[]> pairs) {
            relationships.put(type + ":" + from + ">" + to, pairs);
        }

        NodeRow node(String label, String key) {
            return nodes.get(label).stream().filter(n -> n.key().equals(key)).findFirst().orElseThrow();
        }
    }

    @BeforeEach
    void setUp() {
        store.nodes.clear();
        store.relationships.clear();
    }

    private Path csv(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static Column column(String name, String role, long distinct, long missing) {
        return new Column(name, role, 0.9, distinct, missing);
    }

    /** 20 customers with 3 accounts each; customer C03 has two different emails (a conflict). */
    private TableInput accounts() throws IOException {
        StringBuilder text = new StringBuilder("account_id,customer_id,customer_name,email,country,balance\n");
        for (int i = 0; i < 60; i++) {
            int customer = i % 20;
            String email = "c" + customer + "@mail.com";
            if (customer == 3 && i == 23) {
                email = "other@mail.com";
            }
            text.append("A").append(i).append(",C").append(String.format("%02d", customer)).append(",Name ").append(customer)
                .append(",").append(email).append(",").append(customer % 2 == 0 ? "FR" : "DE").append(",").append(100 + i).append("\n");
        }
        return new TableInput("accounts", csv("accounts.csv", text.toString()), 60, List.of(
            column("account_id", "IDENTIFIER", 60, 0),
            column("customer_id", "REFERENCE", 20, 0),
            column("customer_name", "NAME", 20, 0),
            column("email", "EMAIL", 21, 0),
            column("country", "LOCATION", 2, 0),
            column("balance", "AMOUNT", 60, 0)));
    }

    private static NodeType node(Schema schema, String label) {
        return schema.nodes().stream().filter(n -> n.label().equals(label)).findFirst().orElseThrow(() -> new AssertionError("No node " + label));
    }

    private static List<String> columns(NodeType node) {
        return node.properties().stream().map(Property::column).toList();
    }

    // ------------------------------------------------------------------ what a row stands for

    @Test
    void aRowOfAccountsIsAnAccountAndItsCustomerIsFoundInsideIt() throws IOException {
        Schema schema = modeler.propose(List.of(accounts()), List.of(), Set.of());

        NodeType account = node(schema, "Account");
        assertThat(account.keyColumn()).isEqualTo("account_id");
        assertThat(account.derived()).isFalse();
        assertThat(columns(account)).containsExactly("balance");

        NodeType customer = node(schema, "Customer");
        assertThat(customer.derived()).isTrue();
        assertThat(customer.keyColumn()).isEqualTo("customer_id");
        // These columns keep their value whenever the customer repeats, so they describe the customer.
        assertThat(columns(customer)).containsExactlyInAnyOrder("customer_name", "email", "country");

        RelType relationship = schema.relationships().get(0);
        assertThat(relationship.type()).isEqualTo("HAS_ACCOUNT");
        assertThat(relationship.fromLabel()).isEqualTo("Customer");
        assertThat(relationship.toLabel()).isEqualTo("Account");
        assertThat(relationship.fromColumn()).isEqualTo("customer_id");
        assertThat(relationship.toColumn()).isEqualTo("account_id");
    }

    @Test
    void aCustomerWithTwoEmailsIsAConflictNotAChoice() throws IOException {
        Schema schema = modeler.propose(List.of(accounts()), List.of(), Set.of());

        Finding conflict = schema.findings().stream().filter(f -> f.kind().equals("DEPENDENCY")).findFirst().orElseThrow();
        assertThat(conflict.severity()).isEqualTo("conflict");
        assertThat(conflict.message()).contains("customer_id → email", "1 of 20", "chooses none");
        assertThat(conflict.examples().get(0)).isEqualTo("C03: c3@mail.com | other@mail.com");
    }

    @Test
    void anEmailThatIsMistypedForSomeCustomersStillDescribesTheCustomerAndIsReportedAsConflicts() throws IOException {
        StringBuilder text = new StringBuilder("account_id,customer_id,email\n");
        for (int i = 0; i < 60; i++) {
            int customer = i % 20;
            // Customers 0 to 3 (20% of them) have one account where the email lost its @.
            String email = customer < 4 && i < 20 ? "c" + customer + ".mail.com" : "c" + customer + "@mail.com";
            text.append("A").append(i).append(",C").append(String.format("%02d", customer)).append(",").append(email).append("\n");
        }
        TableInput table = new TableInput("accounts", csv("accounts.csv", text.toString()), 60, List.of(
            column("account_id", "IDENTIFIER", 60, 0), column("customer_id", "REFERENCE", 20, 0), column("email", "EMAIL", 24, 0)));

        Schema schema = modeler.propose(List.of(table), List.of(), Set.of());

        assertThat(columns(node(schema, "Customer"))).containsExactly("email");
        Finding conflict = schema.findings().stream().filter(f -> f.kind().equals("DEPENDENCY")).findFirst().orElseThrow();
        assertThat(conflict.message()).contains("4 of 20");
    }

    @Test
    void aColumnThatIsAlmostConstantIsNotMistakenForADescriptionOfTheCustomer() throws IOException {
        StringBuilder text = new StringBuilder("account_id,customer_id,currency\n");
        for (int i = 0; i < 60; i++) {
            // 57 of 60 rows are EUR: any two rows agree about 90% of the time, whoever the customer is.
            text.append("A").append(i).append(",C").append(String.format("%02d", i % 20)).append(",").append(i >= 57 ? "USD" : "EUR").append("\n");
        }
        TableInput table = new TableInput("accounts", csv("accounts.csv", text.toString()), 60, List.of(
            column("account_id", "IDENTIFIER", 60, 0), column("customer_id", "REFERENCE", 20, 0), column("currency", "CATEGORY", 2, 0)));

        Schema schema = modeler.propose(List.of(table), List.of(), Set.of());

        assertThat(columns(node(schema, "Account"))).containsExactly("currency");
        assertThat(columns(node(schema, "Customer"))).isEmpty();
    }

    @Test
    void aTableWithoutAnIdentifierGivesOneNodePerRowAndAWarning() throws IOException {
        TableInput table = new TableInput("notes", csv("notes.csv", "text\nhello\nhello\n"), 2, List.of(column("text", "FREE_TEXT", 1, 0)));

        Schema schema = modeler.propose(List.of(table), List.of(), Set.of());

        assertThat(node(schema, "Note").keyColumn()).isNull();
        assertThat(schema.findings()).anyMatch(f -> f.kind().equals("NO_KEY") && f.severity().equals("warning"));
    }

    @Test
    void aColumnThatIsDifferentOnEveryRowIsUsedAsTheKeyWhenNoRoleSaysSo() throws IOException {
        TableInput table = new TableInput("things", csv("things.csv", "ref,label\nr1,a\nr2,b\nr3,c\n"), 3,
            List.of(column("ref", "UNKNOWN", 3, 0), column("label", "NAME", 3, 0)));

        Schema schema = modeler.propose(List.of(table), List.of(), Set.of());

        assertThat(node(schema, "Thing").keyColumn()).isEqualTo("ref");
        assertThat(schema.findings()).anyMatch(f -> f.kind().equals("KEY_BY_VALUES"));
    }

    @Test
    void aKeyThatRepeatsIsReportedAsAConflict() throws IOException {
        TableInput table = new TableInput("people", csv("people.csv", "id,name\n1,Ann\n2,Bo\n1,Anna\n,Cy\n"), 4,
            List.of(column("id", "IDENTIFIER", 2, 1), column("name", "NAME", 4, 0)));

        Schema schema = modeler.propose(List.of(table), List.of(), Set.of());

        assertThat(schema.findings()).anyMatch(f -> f.kind().equals("KEY_DUPLICATE") && f.examples().contains("1"));
        assertThat(schema.findings()).anyMatch(f -> f.kind().equals("BLANK_KEY") && f.message().contains("1 row(s)"));
    }

    // ------------------------------------------------------------------ several tables

    @Test
    void aForeignKeyFoundByTheAnalysisBecomesARelationshipBetweenTheTwoTables() throws IOException {
        TableInput customers = new TableInput("customers", csv("customers.csv", "id,name\nC1,Ann\nC2,Bo\n"), 2,
            List.of(column("id", "IDENTIFIER", 2, 0), column("name", "NAME", 2, 0)));
        TableInput orders = new TableInput("orders", csv("orders.csv", "order_id,customer_id,total\nO1,C1,10\nO2,C1,20\nO3,C9,5\n"), 3,
            List.of(column("order_id", "IDENTIFIER", 3, 0), column("customer_id", "REFERENCE", 2, 0), column("total", "AMOUNT", 3, 0)));

        Schema schema = modeler.propose(List.of(customers, orders), List.of(new Link("orders", "customer_id", "customers", "id")), Set.of());

        assertThat(schema.nodes()).extracting(NodeType::label).containsExactly("Customer", "Order");   // no invented node for customer_id
        RelType relationship = schema.relationships().get(0);
        assertThat(relationship.type()).isEqualTo("HAS_ORDER");
        assertThat(relationship.fromLabel()).isEqualTo("Customer");
        assertThat(relationship.toLabel()).isEqualTo("Order");
        assertThat(columns(node(schema, "Order"))).containsExactly("total");

        // Building it: O3 refers to a customer that does not exist, which is said, not hidden.
        BuildResult result = builder.build("d1v2", schema, List.of(customers, orders));
        assertThat(store.cleared).isEqualTo("d1v2");
        assertThat(result.nodes()).containsEntry("Customer", 2L).containsEntry("Order", 3L);
        assertThat(store.relationships.get("HAS_ORDER:Customer>Order")).hasSize(2);
        Finding missing = result.findings().stream().filter(f -> f.kind().equals("MISSING_TARGET")).findFirst().orElseThrow();
        assertThat(missing.message()).contains("1 Order refer to a Customer that does not exist");
        assertThat(missing.examples()).containsExactly("C9");
    }

    @Test
    void aLinkToAColumnThatIsNotTheIdentifierIsNotUsed() throws IOException {
        TableInput customers = new TableInput("customers", csv("customers.csv", "id,code\n1,A\n2,B\n"), 2,
            List.of(column("id", "IDENTIFIER", 2, 0), column("code", "CATEGORY", 2, 0)));
        TableInput orders = new TableInput("orders", csv("orders.csv", "order_id,cust\nO1,A\n"), 1,
            List.of(column("order_id", "IDENTIFIER", 1, 0), column("cust", "REFERENCE", 1, 0)));

        Schema schema = modeler.propose(List.of(customers, orders), List.of(new Link("orders", "cust", "customers", "code")), Set.of());

        assertThat(schema.findings()).anyMatch(f -> f.kind().equals("RELATION_NOT_USED"));
    }

    // ------------------------------------------------------------------ a column that becomes a node

    @Test
    void aCountryCanBecomeANodeOfItsOwnWhenTheUserAsksForIt() throws IOException {
        Schema offered = modeler.propose(List.of(accounts()), List.of(), Set.of());
        assertThat(offered.promotable()).extracting(Promotable::column).contains("country");
        assertThat(node(offered, "Customer").properties()).extracting(Property::column).contains("country");

        Schema schema = modeler.propose(List.of(accounts()), List.of(), Set.of("country"));

        assertThat(node(schema, "Customer").properties()).extracting(Property::column).doesNotContain("country");
        NodeType country = node(schema, "Country");
        assertThat(country.keyColumn()).isEqualTo("country");
        RelType relationship = schema.relationships().stream().filter(r -> r.toLabel().equals("Country")).findFirst().orElseThrow();
        assertThat(relationship.fromLabel()).isEqualTo("Customer");
        assertThat(relationship.type()).isEqualTo("HAS_COUNTRY");
        assertThat(relationship.fromColumn()).isEqualTo("customer_id");
    }

    // ------------------------------------------------------------------ building

    @Test
    void eachNodeIsCreatedOnceAndAConflictKeepsEveryValueAndChoosesNone() throws IOException {
        TableInput accounts = accounts();
        Schema schema = modeler.propose(List.of(accounts), List.of(), Set.of());

        BuildResult result = builder.build("d1v1", schema, List.of(accounts));

        assertThat(result.nodes()).containsEntry("Account", 60L).containsEntry("Customer", 20L);
        assertThat(store.relationships.get("HAS_ACCOUNT:Customer>Account")).hasSize(60);

        NodeRow clean = store.node("Customer", "C00");
        assertThat(clean.properties()).containsEntry("email", "c0@mail.com").containsEntry("_rows", 3L);
        assertThat(clean.properties()).doesNotContainKey("_conflict");

        NodeRow conflicting = store.node("Customer", "C03");
        assertThat(conflicting.properties()).doesNotContainKey("email");
        assertThat(conflicting.properties().get("email__candidates")).isEqualTo(List.of("c3@mail.com", "other@mail.com"));
        assertThat(conflicting.properties()).containsEntry("_conflict", true).containsEntry("_conflicts", List.of("email"));
        assertThat(conflicting.properties()).containsEntry("customer_name", "Name 3");

        Finding conflict = result.findings().stream().filter(f -> f.kind().equals("PROPERTY_CONFLICT")).findFirst().orElseThrow();
        assertThat(conflict.message()).contains("Customer.email: 1 node(s)");
    }

    @Test
    void amountsAreStoredAsNumbersAndOtherValuesStayText() throws IOException {
        TableInput accounts = accounts();
        Schema schema = modeler.propose(List.of(accounts), List.of(), Set.of());

        builder.build("d1v1", schema, List.of(accounts));

        assertThat(store.node("Account", "A7").properties()).containsEntry("balance", 107L);
        assertThat(store.node("Account", "A7").properties()).containsEntry("name", "A7");
    }

    @Test
    void rowsThatShareAKeyAreMergedIntoOneNode() throws IOException {
        TableInput people = new TableInput("people", csv("people.csv", "id,name\n1,Ann\n2,Bo\n1,Anna\n,Cy\n"), 4,
            List.of(column("id", "IDENTIFIER", 2, 1), column("name", "NAME", 4, 0)));
        Schema schema = modeler.propose(List.of(people), List.of(), Set.of());

        BuildResult result = builder.build("d1v1", schema, List.of(people));

        assertThat(result.nodes()).containsEntry("Person", 2L);
        assertThat(store.node("Person", "1").properties().get("name__candidates")).isEqualTo(List.of("Ann", "Anna"));
        assertThat(result.findings()).anyMatch(f -> f.kind().equals("BLANK_KEY"));
        assertThat(result.findings()).anyMatch(f -> f.kind().equals("BUILT"));
        List<String> kinds = new ArrayList<>();
        result.findings().forEach(f -> kinds.add(f.kind()));
        assertThat(kinds).contains("KEY_DUPLICATE", "PROPERTY_CONFLICT");
    }

    // ------------------------------------------------------------------ names

    @Test
    void labelsAndRelationshipTypesAreSafeNames() {
        assertThat(GraphModeler.label("accounts", true)).isEqualTo("Account");
        assertThat(GraphModeler.label("customer_id", false)).isEqualTo("Customer");
        assertThat(GraphModeler.label("Unclean Dataset", true)).isEqualTo("UncleanDataset");
        assertThat(GraphModeler.label("orderLines", true)).isEqualTo("OrderLine");
        assertThat(GraphModeler.label("categories", true)).isEqualTo("Category");
        assertThat(GraphModeler.label("2024 sales", true)).isEqualTo("N2024Sale");
        assertThat(GraphModeler.label("id", false)).isEqualTo("Id");
        assertThat(GraphModeler.label("évènement; DROP", true)).matches("[A-Za-z][A-Za-z0-9]*");
        assertThat(GraphModeler.snake("OrderLine")).isEqualTo("ORDER_LINE");
    }

    // ------------------------------------------------------------------ files with no identifier column

    /** Users numbered by their line, posts that name a user by that number, friendships between two numbers. */
    private List<TableInput> social(boolean reactionsFit) throws IOException {
        StringBuilder users = new StringBuilder("Surname,Name,Age\n");
        for (int i = 1; i <= 40; i++) {
            users.append("S").append(i % 7).append(",N").append(i % 5).append(",").append(20 + i % 30).append("\n");
        }
        StringBuilder posts = new StringBuilder("User,Post Type\n");
        StringBuilder friends = new StringBuilder("Friend 1,Friend 2\n");
        StringBuilder reactions = new StringBuilder("User,Reaction Type\n");
        for (int i = 1; i <= 40; i++) {
            posts.append(i).append(",Text\n").append(i).append(",Image\n");
            for (int d = 1; d <= 3; d++) {
                friends.append(i).append(",").append((i + d - 1) % 40 + 1).append("\n");
            }
        }
        for (int i = 1; i <= 100; i++) {
            reactions.append(reactionsFit ? (i - 1) % 40 + 1 : i * 7).append(",Like\n");
        }
        return List.of(
            new TableInput("friends_table", csv("friends_table.csv", friends.toString()), 120, List.of(
                column("Friend 1", "REFERENCE", 40, 0), column("Friend 2", "REFERENCE", 40, 0))),
            new TableInput("posts_table", csv("posts_table.csv", posts.toString()), 80, List.of(
                column("User", "REFERENCE", 40, 0), column("Post Type", "CATEGORY", 2, 0))),
            new TableInput("reactions_table", csv("reactions_table.csv", reactions.toString()), 100, List.of(
                column("User", "REFERENCE", 40, 0), column("Reaction Type", "CATEGORY", 1, 0))),
            new TableInput("user_table", csv("user_table.csv", users.toString()), 40, List.of(
                column("Surname", "NAME", 7, 0), column("Name", "NAME", 5, 0), column("Age", "QUANTITY", 30, 0))));
    }

    private static boolean has(Schema schema, String kind) {
        return schema.findings().stream().anyMatch(f -> f.kind().equals(kind));
    }

    @Test
    void rowsWithoutAnIdentifierAreReachedByTheirLineNumber() throws IOException {
        Schema schema = modeler.propose(social(true), List.of(), Set.of());

        // Table names say what the rows are, not that they are a table.
        assertThat(schema.nodes()).extracting(NodeType::label).contains("User", "Post", "Reaction");
        // The user column of the posts names a row of user_table, so no invented "User" node appears next to the real one.
        assertThat(schema.nodes().stream().filter(NodeType::derived)).isEmpty();
        RelType posted = schema.relationships().stream().filter(r -> r.toLabel().equals("Post")).findFirst().orElseThrow();
        assertThat(posted.fromLabel()).isEqualTo("User");
        assertThat(posted.fromByPosition()).isTrue();
        assertThat(posted.toByPosition()).isFalse();
        assertThat(has(schema, "DISCONNECTED")).isFalse();
    }

    @Test
    void aTableOfTwoReferencesIsARelationshipAndNotANode() throws IOException {
        Schema schema = modeler.propose(social(true), List.of(), Set.of());

        assertThat(schema.nodes()).extracting(NodeType::table).doesNotContain("friends_table");
        RelType friend = schema.relationships().stream().filter(r -> r.table().equals("friends_table")).findFirst().orElseThrow();
        assertThat(friend.type()).isEqualTo("FRIEND");
        assertThat(friend.fromLabel()).isEqualTo("User");
        assertThat(friend.toLabel()).isEqualTo("User");
        assertThat(friend.fromByPosition()).isTrue();
        assertThat(friend.toByPosition()).isTrue();
        assertThat(has(schema, "LINK_TABLE")).isTrue();
    }

    @Test
    void theBuildJoinsTheRowsByTheirPosition() throws IOException {
        List<TableInput> tables = social(true);
        Schema schema = modeler.propose(tables, List.of(), Set.of());
        BuildResult result = builder.build("d1v1", schema, tables);

        assertThat(result.nodes()).containsEntry("User", 40L).containsEntry("Post", 80L);
        // User 1 is the first line of user_table, and posted two posts.
        assertThat(store.relationships.get("HAS_POST:User>Post")).hasSize(80);
        assertThat(store.relationships.get("HAS_POST:User>Post")).anyMatch(pair -> pair[0].equals("row-1") && pair[1].startsWith("row-"));
        assertThat(store.relationships.get("FRIEND:User>User")).hasSize(120).anyMatch(pair -> pair[0].equals("row-40") && pair[1].equals("row-1"));
        assertThat(result.findings()).noneMatch(f -> f.kind().equals("MISSING_TARGET"));
    }

    @Test
    void aColumnThatOnlyPartlyFitsAsLineNumbersIsNotLinkedAndSaysWhy() throws IOException {
        Schema schema = modeler.propose(social(false), List.of(), Set.of());

        // Only 5 of 100 values (7, 14, ... 35) are inside 1..40 here, so the column is not taken for a reference to the users.
        assertThat(schema.relationships()).noneMatch(r -> r.table().equals("reactions_table") && r.fromByPosition());
        assertThat(has(schema, "PARTIAL_LINK")).isFalse(); // below the 30% that deserves a remark
        assertThat(has(schema, "DISCONNECTED")).isTrue();
        assertThat(schema.findings().stream().filter(f -> f.kind().equals("DISCONNECTED")).findFirst().orElseThrow().message())
            .contains("reactions_table");
    }

    @Test
    void aSmallCodeIsNotTakenForALineNumber() throws IOException {
        StringBuilder rows = new StringBuilder("Name\n");
        StringBuilder codes = new StringBuilder("Code,Kind\n");
        for (int i = 1; i <= 100; i++) {
            rows.append("N").append(i).append("\n");
            codes.append(i % 5 + 1).append(",x\n");
        }
        Schema schema = modeler.propose(List.of(
            new TableInput("people", csv("people.csv", rows.toString()), 100, List.of(column("Name", "NAME", 100, 0))),
            new TableInput("events", csv("events.csv", codes.toString()), 100, List.of(column("Code", "REFERENCE", 5, 0), column("Kind", "CATEGORY", 1, 0)))),
            List.of(), Set.of());

        assertThat(schema.relationships()).noneMatch(RelType::fromByPosition);
    }

    @Test
    void aTableThatBecomesARelationshipIsNeverTheTargetOfAPosition() throws IOException {
        // The reactions' user column also fits the line numbers of the (long) friendships file; that file has no nodes.
        List<TableInput> tables = social(true);
        StringBuilder many = new StringBuilder("User,Reaction Type\n");
        for (int i = 1; i <= 100; i++) {
            many.append(i * 3).append(",Like\n");
        }
        List<TableInput> withLongReactions = new ArrayList<>(tables);
        withLongReactions.set(2, new TableInput("reactions_table", csv("reactions_table.csv", many.toString()), 100, List.of(
            column("User", "REFERENCE", 100, 0), column("Reaction Type", "CATEGORY", 1, 0))));

        Schema schema = modeler.propose(withLongReactions, List.of(), Set.of());

        Set<String> labels = new java.util.HashSet<>();
        schema.nodes().forEach(n -> labels.add(n.label()));
        assertThat(schema.relationships()).allSatisfy(r -> assertThat(labels).contains(r.fromLabel(), r.toLabel()));
    }

    @Test
    void aSingleTableIsNeverReportedAsDisconnected() throws IOException {
        assertThat(has(modeler.propose(List.of(accounts()), List.of(), Set.of()), "DISCONNECTED")).isFalse();
    }

    // ------------------------------------------------------------------ keys of several columns, rows that point at rows

    /** 5 orders, 4 products, 12 order lines (order_id with a line number), and stock per store and product. */
    private List<TableInput> shop() throws IOException {
        StringBuilder orders = new StringBuilder("order_id,placed\n");
        for (int i = 1; i <= 5; i++) {
            orders.append("O").append(i).append(",2024-01-0").append(i).append("\n");
        }
        StringBuilder products = new StringBuilder("product_id,label\n");
        for (int i = 1; i <= 4; i++) {
            products.append("P").append(i).append(",Product ").append(i).append("\n");
        }
        StringBuilder lines = new StringBuilder("order_id,item_id,product_id,quantity\n");
        for (int i = 0; i < 12; i++) {
            lines.append("O").append(i % 5 + 1).append(",").append(i / 5 + 1).append(",P").append(i % 4 + 1).append(",").append(i % 5 + 1).append("\n");
        }
        StringBuilder stocks = new StringBuilder("store_id,product_id,quantity\n");
        for (int store = 1; store <= 3; store++) {
            for (int product = 1; product <= 4; product++) {
                stocks.append("S").append(store).append(",P").append(product).append(",").append(store * product).append("\n");
            }
        }
        StringBuilder stores = new StringBuilder("store_id,city\n");
        for (int i = 1; i <= 3; i++) {
            stores.append("S").append(i).append(",City ").append(i).append("\n");
        }
        return List.of(
            new TableInput("orders", csv("orders.csv", orders.toString()), 5, List.of(column("order_id", "IDENTIFIER", 5, 0), column("placed", "DATE", 5, 0))),
            new TableInput("products", csv("products.csv", products.toString()), 4, List.of(column("product_id", "IDENTIFIER", 4, 0), column("label", "NAME", 4, 0))),
            new TableInput("stores", csv("stores.csv", stores.toString()), 3, List.of(column("store_id", "IDENTIFIER", 3, 0), column("city", "LOCATION", 3, 0))),
            new TableInput("order_items", csv("order_items.csv", lines.toString()), 12, List.of(
                column("order_id", "REFERENCE", 5, 0), column("item_id", "REFERENCE", 3, 0), column("product_id", "REFERENCE", 4, 0), column("quantity", "QUANTITY", 5, 0))),
            new TableInput("stocks", csv("stocks.csv", stocks.toString()), 12, List.of(
                column("store_id", "REFERENCE", 3, 0), column("product_id", "REFERENCE", 4, 0), column("quantity", "QUANTITY", 9, 0))));
    }

    private static List<Link> shopLinks() {
        return List.of(new Link("order_items", "order_id", "orders", "order_id"), new Link("order_items", "product_id", "products", "product_id"),
            new Link("stocks", "store_id", "stores", "store_id"), new Link("stocks", "product_id", "products", "product_id"));
    }

    @Test
    void rowsToldApartByTwoReferencesAreIdentifiedByBothTogether() throws IOException {
        Schema schema = modeler.propose(shop(), shopLinks(), Set.of());

        NodeType line = node(schema, "OrderItem");
        assertThat(line.keyColumn()).isEqualTo("order_id + item_id");
        // The line number is part of the key and is kept as a property; it is not a thing of its own (no "Item" node).
        assertThat(columns(line)).contains("item_id", "quantity");
        assertThat(schema.nodes()).extracting(NodeType::label).doesNotContain("Item");
        assertThat(node(schema, "Stock").keyColumn()).isEqualTo("store_id + product_id");
        // Both references still link the line to the order and the product.
        assertThat(schema.relationships()).anyMatch(r -> r.fromLabel().equals("Order") && r.toLabel().equals("OrderItem") && r.fromColumn().equals("order_id"));
        assertThat(schema.relationships()).anyMatch(r -> r.fromLabel().equals("Product") && r.toLabel().equals("OrderItem"));
        assertThat(schema.findings()).anyMatch(f -> f.kind().equals("COMPOSITE_KEY") && f.message().contains("order_id and item_id"));
        assertThat(has(schema, "NO_KEY")).isFalse();
    }

    @Test
    void theBuildGivesEveryLineItsOwnNodeAndLinksItToItsParents() throws IOException {
        List<TableInput> tables = shop();
        BuildResult result = builder.build("d1v1", modeler.propose(tables, shopLinks(), Set.of()), tables);

        assertThat(result.nodes()).containsEntry("OrderItem", 12L).containsEntry("Stock", 12L);
        assertThat(store.node("OrderItem", "O1|1").properties()).containsEntry("quantity", 1L);
        assertThat(store.relationships.get("HAS_ORDER_ITEM:Order>OrderItem")).hasSize(12)
            .anyMatch(pair -> pair[0].equals("O1") && pair[1].equals("O1|1"));
        assertThat(result.findings()).noneMatch(f -> f.kind().equals("MISSING_TARGET"));
    }

    @Test
    void aCombinationThatRepeatsIsNotAKey() throws IOException {
        // The same order and product twice: neither pair of references is unique, so the rows stay unidentified.
        String text = "order_id,product_id,quantity\nO1,P1,1\nO1,P1,2\nO2,P1,3\n";
        TableInput lines = new TableInput("order_items", csv("order_items.csv", text), 3, List.of(
            column("order_id", "REFERENCE", 2, 0), column("product_id", "REFERENCE", 1, 0), column("quantity", "QUANTITY", 2, 0)));

        Schema schema = modeler.propose(List.of(lines), List.of(), Set.of());

        assertThat(node(schema, "OrderItem").keyColumn()).isNull();
        assertThat(has(schema, "COMPOSITE_KEY")).isFalse();
        assertThat(has(schema, "NO_KEY")).isTrue();
    }

    @Test
    void aTableOfTwoReferencesStaysARelationshipAndIsNotGivenACompositeKey() throws IOException {
        String text = "product_id,store_id\nP1,S1\nP2,S1\nP1,S2\n";
        List<TableInput> tables = new ArrayList<>(shop());
        tables.add(new TableInput("product_stores", csv("product_stores.csv", text), 3, List.of(
            column("product_id", "REFERENCE", 2, 0), column("store_id", "REFERENCE", 2, 0))));
        List<Link> links = new ArrayList<>(shopLinks());
        links.add(new Link("product_stores", "product_id", "products", "product_id"));
        links.add(new Link("product_stores", "store_id", "stores", "store_id"));

        Schema schema = modeler.propose(tables, links, Set.of());

        assertThat(schema.nodes()).extracting(NodeType::table).doesNotContain("product_stores");
        assertThat(has(schema, "LINK_TABLE")).isTrue();
    }

    private TableInput staff(String managerFor) throws IOException {
        StringBuilder text = new StringBuilder("staff_id,name,manager_id\n");
        for (int i = 1; i <= 8; i++) {
            text.append("E").append(i).append(",Name ").append(i).append(",").append(i == 1 ? "" : managerFor.replace("#", String.valueOf(i / 3 + 1))).append("\n");
        }
        return new TableInput("staff", csv("staff.csv", text.toString()), 8, List.of(
            column("staff_id", "IDENTIFIER", 8, 0), column("name", "NAME", 8, 0), column("manager_id", "REFERENCE", 3, 1)));
    }

    @Test
    void aColumnOfIdentifiersOfTheSameTableLinksTwoRowsOfThatTable() throws IOException {
        Schema schema = modeler.propose(List.of(staff("E#")), List.of(), Set.of());

        assertThat(schema.nodes()).extracting(NodeType::label).containsExactly("Staff");   // no empty "Manager" node
        RelType manager = schema.relationships().get(0);
        assertThat(manager.type()).isEqualTo("MANAGER");
        assertThat(manager.fromLabel()).isEqualTo("Staff");
        assertThat(manager.toLabel()).isEqualTo("Staff");
        assertThat(manager.fromColumn()).isEqualTo("staff_id");
        assertThat(manager.toColumn()).isEqualTo("manager_id");
        assertThat(has(schema, "SELF_REFERENCE")).isTrue();
    }

    @Test
    void theBuildDrawsWhoWorksForWhom() throws IOException {
        List<TableInput> tables = List.of(staff("E#"));
        BuildResult result = builder.build("d1v1", modeler.propose(tables, List.of(), Set.of()), tables);

        assertThat(result.nodes()).containsEntry("Staff", 8L);
        assertThat(store.relationships.get("MANAGER:Staff>Staff")).hasSize(7)
            .anyMatch(pair -> pair[0].equals("E4") && pair[1].equals("E2"));
    }

    @Test
    void aColumnThatPointsAtNobodyInTheTableIsStillADerivedThing() throws IOException {
        // The managers E91..E93 are not staff of this file: the column is a reference to something else.
        Schema schema = modeler.propose(List.of(staff("E9#")), List.of(), Set.of());

        assertThat(schema.nodes()).extracting(NodeType::label).contains("Staff", "Manager");
        assertThat(has(schema, "SELF_REFERENCE")).isFalse();
    }

    @Test
    void aNullMarkerIsNobodyAndNotARowOrAReference() throws IOException {
        // The first staff member has no manager, written NULL like in many exports.
        StringBuilder text = new StringBuilder("staff_id,name,manager_id\n");
        for (int i = 1; i <= 8; i++) {
            text.append("E").append(i).append(",Name ").append(i).append(",").append(i == 1 ? "NULL" : "E" + (i / 3 + 1)).append("\n");
        }
        List<TableInput> tables = List.of(new TableInput("staff", csv("staff.csv", text.toString()), 8, List.of(
            column("staff_id", "IDENTIFIER", 8, 0), column("name", "NAME", 8, 0), column("manager_id", "REFERENCE", 4, 0))));

        Schema schema = modeler.propose(tables, List.of(), Set.of());
        BuildResult result = builder.build("d1v1", schema, tables);

        assertThat(schema.nodes()).extracting(NodeType::label).containsExactly("Staff");
        assertThat(store.relationships.get("MANAGER:Staff>Staff")).hasSize(7).noneMatch(pair -> pair[0].equals("E1"));
        assertThat(result.nodes()).containsEntry("Staff", 8L);
    }
}
