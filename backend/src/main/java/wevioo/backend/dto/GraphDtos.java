package wevioo.backend.dto;

import wevioo.backend.graph.GraphModel.BuildResult;
import wevioo.backend.graph.GraphModel.Schema;

import java.util.List;
import java.util.Map;

public class GraphDtos {
    /**
     * @param promote columns that become nodes of their own ("country" or "customers.country")
     * @param renames the names chosen for labels ("label:Customer") and relationship types ("type:HAS_ACCOUNT")
     */
    public record BuildRequest(List<String> promote, Map<String, String> renames) {}

    /** The schema that was built and what the build produced. */
    public record GraphBuild(Schema schema, BuildResult result) {}

    /** Whether Neo4j can be used, and where to look at the graph. */
    public record GraphStatus(boolean available, String message, String browserUrl, String scope) {}
}
