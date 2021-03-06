package org.visallo.core.model.termMention;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.vertexium.*;
import org.vertexium.mutation.ElementMutation;
import org.vertexium.mutation.ExistingElementMutation;
import org.vertexium.util.FilterIterable;
import org.vertexium.util.IterableUtils;
import org.vertexium.util.JoinIterable;
import org.visallo.core.model.PropertyJustificationMetadata;
import org.visallo.core.model.graph.GraphRepository;
import org.visallo.core.model.graph.GraphUpdateContext;
import org.visallo.core.model.properties.VisalloProperties;
import org.visallo.core.model.user.GraphAuthorizationRepository;
import org.visallo.core.model.workQueue.Priority;
import org.visallo.core.model.workQueue.WorkQueueRepository;
import org.visallo.core.security.VisalloVisibility;
import org.visallo.core.security.VisibilityTranslator;
import org.visallo.core.util.*;
import org.visallo.web.clientapi.model.ClientApiSourceInfo;
import org.visallo.web.clientapi.model.ClientApiTermMentionsResponse;
import org.visallo.web.clientapi.model.SandboxStatus;
import org.visallo.web.clientapi.model.VisibilityJson;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.vertexium.util.IterableUtils.single;
import static org.vertexium.util.IterableUtils.singleOrDefault;
import static org.visallo.core.util.StreamUtil.stream;

@Singleton
public class TermMentionRepository {
    private static final VisalloLogger LOGGER = VisalloLoggerFactory.getLogger(TermMentionRepository.class);
    public static final String VISIBILITY_STRING = "termMention";
    public static final String OWL_IRI = "http://visallo.org/termMention";
    private final VisibilityTranslator visibilityTranslator;
    private final WorkQueueRepository workQueueRepository;
    private final Graph graph;

    @Inject
    public TermMentionRepository(
            Graph graph,
            VisibilityTranslator visibilityTranslator,
            WorkQueueRepository workQueueRepository,
            GraphAuthorizationRepository graphAuthorizationRepository
    ) {
        this.graph = graph;
        this.visibilityTranslator = visibilityTranslator;
        this.workQueueRepository = workQueueRepository;
        graphAuthorizationRepository.addAuthorizationToGraph(VISIBILITY_STRING);
    }

    public Iterable<Vertex> findByOutVertexAndProperty(
            String outVertexId,
            String propertyKey,
            String propertyName,
            Authorizations authorizations
    ) {
        authorizations = getAuthorizations(authorizations);
        return new FilterIterable<Vertex>(findByOutVertex(outVertexId, authorizations)) {
            @Override
            protected boolean isIncluded(Vertex v) {
                String vertexPropertyKey = VisalloProperties.TERM_MENTION_PROPERTY_KEY.getPropertyValue(v);
                if (!propertyKey.equals(vertexPropertyKey)) {
                    return false;
                }

                // handle legacy data which did not have property name
                String vertexPropertyName = VisalloProperties.TERM_MENTION_PROPERTY_NAME.getPropertyValue(v, null);
                if (VisalloProperties.TEXT.getPropertyName().equals(propertyName) && vertexPropertyName == null) {
                    return true;
                }

                return propertyName.equals(vertexPropertyName);
            }
        };
    }

    public Iterable<Vertex> findByOutVertex(String outVertexId, Authorizations authorizations) {
        Authorizations authorizationsWithTermMention = getAuthorizations(authorizations);
        Vertex outVertex = graph.getVertex(outVertexId, authorizationsWithTermMention);
        return outVertex.getVertices(
                Direction.OUT,
                VisalloProperties.TERM_MENTION_LABEL_HAS_TERM_MENTION,
                authorizationsWithTermMention
        );
    }

    /**
     * Find all term mentions connected to the vertex.
     */
    public Iterable<Vertex> findByVertexId(String vertexId, Authorizations authorizations) {
        Authorizations authorizationsWithTermMention = getAuthorizations(authorizations);
        Vertex vertex = graph.getVertex(vertexId, authorizationsWithTermMention);
        String[] labels = new String[]{
                VisalloProperties.TERM_MENTION_LABEL_HAS_TERM_MENTION,
                VisalloProperties.TERM_MENTION_LABEL_RESOLVED_TO
        };
        return vertex.getVertices(Direction.BOTH, labels, authorizationsWithTermMention);
    }

    /**
     * Find all term mentions connected to either side of the edge.
     */
    public Iterable<Vertex> findByEdge(Edge edge, Authorizations authorizations) {
        return new JoinIterable<>(
                findByVertexId(edge.getVertexId(Direction.IN), authorizations),
                findByVertexId(edge.getVertexId(Direction.OUT), authorizations)
        );
    }

    /**
     * Finds term mention vertices that were created for the justification of a new vertex.
     *
     * @param vertexId The vertex id of the vertex with the justification.
     * @return term mention vertices matching the criteria.
     */
    public Iterable<Vertex> findByVertexIdForVertex(final String vertexId, Authorizations authorizations) {
        return new FilterIterable<Vertex>(findByVertexId(vertexId, authorizations)) {
            @Override
            protected boolean isIncluded(Vertex termMention) {
                String forElementId = VisalloProperties.TERM_MENTION_FOR_ELEMENT_ID.getPropertyValue(termMention);
                if (forElementId == null || !forElementId.equals(vertexId)) {
                    return false;
                }

                TermMentionFor forType = VisalloProperties.TERM_MENTION_FOR_TYPE.getPropertyValue(termMention);
                if (forType == null || forType != TermMentionFor.VERTEX) {
                    return false;
                }

                return true;
            }
        };
    }

    /**
     * Finds term mention vertices that were created for the justification of a new edge.
     *
     * @param edge The edge id of the edge with the justification.
     * @return term mention vertices matching the criteria.
     */
    public Iterable<Vertex> findByEdgeForEdge(final Edge edge, Authorizations authorizations) {
        return new FilterIterable<Vertex>(findByEdge(edge, authorizations)) {
            @Override
            protected boolean isIncluded(Vertex termMention) {
                String forElementId = VisalloProperties.TERM_MENTION_FOR_ELEMENT_ID.getPropertyValue(termMention);
                if (forElementId == null || !forElementId.equals(edge.getId())) {
                    return false;
                }

                TermMentionFor forType = VisalloProperties.TERM_MENTION_FOR_TYPE.getPropertyValue(termMention);
                if (forType == null || forType != TermMentionFor.EDGE) {
                    return false;
                }

                return true;
            }
        };
    }

    /**
     * Finds all term mentions connected to a vertex that match propertyKey, propertyName, and propertyVisibility.
     */
    public Iterable<Vertex> findByVertexIdAndProperty(
            final String vertexId,
            final String propertyKey,
            final String propertyName,
            final Visibility propertyVisibility,
            Authorizations authorizations
    ) {
        return new FilterIterable<Vertex>(findByVertexId(vertexId, authorizations)) {
            @Override
            protected boolean isIncluded(Vertex termMention) {
                String forElementId = VisalloProperties.TERM_MENTION_FOR_ELEMENT_ID.getPropertyValue(termMention);
                if (forElementId == null || !forElementId.equals(vertexId)) {
                    return false;
                }
                return isTermMentionForProperty(termMention, propertyKey, propertyName, propertyVisibility);
            }
        };
    }

    /**
     * Finds all term mentions connected to either side of an edge that match propertyKey, propertyName, and propertyVisibility.
     */
    public Iterable<Vertex> findByEdgeIdAndProperty(
            final Edge edge,
            final String propertyKey,
            final String propertyName,
            final Visibility propertyVisibility,
            Authorizations authorizations
    ) {
        return new FilterIterable<Vertex>(findByEdge(edge, authorizations)) {
            @Override
            protected boolean isIncluded(Vertex termMention) {
                String forElementId = VisalloProperties.TERM_MENTION_FOR_ELEMENT_ID.getPropertyValue(termMention);
                if (forElementId == null || !forElementId.equals(edge.getId())) {
                    return false;
                }
                return isTermMentionForProperty(termMention, propertyKey, propertyName, propertyVisibility);
            }
        };
    }

    private boolean isTermMentionForProperty(
            Vertex termMention,
            String propertyKey,
            String propertyName,
            Visibility propertyVisibility
    ) {
        TermMentionFor forType = VisalloProperties.TERM_MENTION_FOR_TYPE.getPropertyValue(termMention);
        if (forType == null || forType != TermMentionFor.PROPERTY) {
            return false;
        }

        String refPropertyKey = VisalloProperties.TERM_MENTION_REF_PROPERTY_KEY.getPropertyValue(termMention);
        if (refPropertyKey == null || !refPropertyKey.equals(propertyKey)) {
            return false;
        }

        String refPropertyName = VisalloProperties.TERM_MENTION_REF_PROPERTY_NAME.getPropertyValue(termMention);
        if (refPropertyName == null || !refPropertyName.equals(propertyName)) {
            return false;
        }

        String refPropertyVisibilityString = VisalloProperties.TERM_MENTION_REF_PROPERTY_VISIBILITY.getPropertyValue(
                termMention
        );
        if (refPropertyVisibilityString == null || !refPropertyVisibilityString.equals(propertyVisibility.getVisibilityString())) {
            return false;
        }

        return true;
    }

    public Vertex findById(String termMentionId, Authorizations authorizations) {
        Authorizations authorizationsWithTermMention = getAuthorizations(authorizations);
        return graph.getVertex(termMentionId, authorizationsWithTermMention);
    }

    public void updateEdgeVisibility(String vertexId, String newVisibilitySource, String workspaceId, Authorizations authorizations) {
        Authorizations authorizationsWithTermMention = getAuthorizations(authorizations);
        Iterable<Vertex> termMentions = this.findByVertexIdForVertex(vertexId, authorizationsWithTermMention);
        Iterable<String> edgeIds = stream(termMentions)
                .map(VisalloProperties.TERM_MENTION_RESOLVED_EDGE_ID::getPropertyValue)
                .filter(id -> id != null)
                .collect(Collectors.toList());

        List<Edge> edges = stream(graph.getEdges(edgeIds, authorizations)).map(edge -> {
            ExistingElementMutation<Edge> m = edge.prepareMutation();
            VisibilityJson visibilityJson = SandboxStatusUtil.getSandboxStatus(edge, workspaceId) != SandboxStatus.PUBLIC
                    ? VisibilityJson.updateVisibilitySourceAndAddWorkspaceId(null, newVisibilitySource, workspaceId)
                    : VisibilityJson.updateVisibilitySourceAndAddWorkspaceId(null, newVisibilitySource, null);
            Visibility visibility = visibilityTranslator.toVisibilityNoSuperUser(visibilityJson);
            Visibility defaultVisibility = visibilityTranslator.getDefaultVisibility();

            m.alterElementVisibility(visibility);
            VisalloProperties.VISIBILITY_JSON.setProperty(m, visibilityJson, defaultVisibility);

            return m.save(authorizations);
        }).collect(Collectors.toList());

        graph.flush();

        stream(edges).forEach(edge -> {
            this.workQueueRepository.pushGraphPropertyQueue(
                    edge,
                    null,
                    VisalloProperties.VISIBILITY_JSON.getPropertyName(),
                    workspaceId,
                    newVisibilitySource,
                    Priority.HIGH
            );
        });
    }

    public void updateVisibility(Vertex termMention, Visibility newVisibility, Authorizations authorizations) {
        Authorizations authorizationsWithTermMention = getAuthorizations(authorizations);
        Visibility newVisibilityWithTermMention = VisalloVisibility.and(newVisibility, VISIBILITY_STRING);
        ExistingElementMutation<Vertex> m = termMention.prepareMutation();
        m.alterElementVisibility(newVisibilityWithTermMention);
        for (Property property : termMention.getProperties()) {
            m.alterPropertyVisibility(property, newVisibilityWithTermMention);
        }
        Property refPropertyVisibility = VisalloProperties.TERM_MENTION_REF_PROPERTY_VISIBILITY.getProperty(termMention);
        if (refPropertyVisibility != null) {
            VisalloProperties.TERM_MENTION_REF_PROPERTY_VISIBILITY.setProperty(
                    m,
                    newVisibility.getVisibilityString(),
                    refPropertyVisibility.getMetadata(),
                    newVisibilityWithTermMention
            );
        }
        m.save(authorizationsWithTermMention);
        for (Edge edge : termMention.getEdges(Direction.BOTH, authorizationsWithTermMention)) {
            ExistingElementMutation<Edge> edgeMutation = edge.prepareMutation();
            edgeMutation.alterElementVisibility(newVisibilityWithTermMention);
            for (Property property : edge.getProperties()) {
                edgeMutation.alterPropertyVisibility(property, newVisibilityWithTermMention);
            }
            edgeMutation.save(authorizationsWithTermMention);
        }
    }

    public Iterable<Vertex> findResolvedTo(String inVertexId, Authorizations authorizations) {
        Authorizations authorizationsWithTermMention = getAuthorizations(authorizations);
        Vertex inVertex = graph.getVertex(inVertexId, authorizationsWithTermMention);
        return inVertex.getVertices(
                Direction.IN,
                VisalloProperties.TERM_MENTION_LABEL_RESOLVED_TO,
                authorizationsWithTermMention
        );
    }

    public Stream<Vertex> findResolvedToForRef(
            String inVertexId,
            String refPropertyKey,
            String refPropertyName,
            Authorizations authorizations
    ) {
        checkNotNull(refPropertyKey, "refPropertyKey cannot be null");
        checkNotNull(refPropertyName, "refPropertyName cannot be null");

        return stream(findResolvedTo(inVertexId, authorizations))
                .filter(vertex -> {
                    String vertexRefPropertyKey = VisalloProperties.TERM_MENTION_REF_PROPERTY_KEY.getPropertyValue(
                            vertex,
                            null
                    );
                    String vertexRefPropertyName = VisalloProperties.TERM_MENTION_REF_PROPERTY_NAME.getPropertyValue(
                            vertex,
                            null
                    );
                    return refPropertyKey.equals(vertexRefPropertyKey) && refPropertyName.equals(vertexRefPropertyName);
                });
    }

    /**
     * Gets all the resolve to term mentions for the element not a particular property.
     */
    public Stream<Vertex> findResolvedToForRefElement(String inVertexId, Authorizations authorizations) {
        return stream(findResolvedTo(inVertexId, authorizations))
                .filter(vertex -> {
                    String vertexRefPropertyKey = VisalloProperties.TERM_MENTION_REF_PROPERTY_KEY.getPropertyValue(
                            vertex,
                            null
                    );
                    String vertexRefPropertyName = VisalloProperties.TERM_MENTION_REF_PROPERTY_NAME.getPropertyValue(
                            vertex,
                            null
                    );
                    return vertexRefPropertyKey == null && vertexRefPropertyName == null;
                });
    }

    public void delete(Vertex termMention, Authorizations authorizations) {
        Authorizations authorizationsWithTermMention = getAuthorizations(authorizations);
        graph.softDeleteVertex(termMention, authorizationsWithTermMention);
    }

    public void markHidden(Vertex termMention, Visibility hiddenVisibility, Authorizations authorizations) {
        Authorizations authorizationsWithTermMention = getAuthorizations(authorizations);
        graph.markVertexHidden(termMention, hiddenVisibility, authorizationsWithTermMention);
    }

    public Iterable<Vertex> findByEdgeId(String outVertexId, final String edgeId, Authorizations authorizations) {
        Authorizations authorizationsWithTermMention = getAuthorizations(authorizations);
        Vertex outVertex = graph.getVertex(outVertexId, authorizationsWithTermMention);
        return new FilterIterable<Vertex>(outVertex.getVertices(
                Direction.OUT,
                VisalloProperties.TERM_MENTION_LABEL_HAS_TERM_MENTION,
                authorizationsWithTermMention
        )) {
            @Override
            protected boolean isIncluded(Vertex v) {
                String vertexEdgeId = VisalloProperties.TERM_MENTION_RESOLVED_EDGE_ID.getPropertyValue(v);
                return edgeId.equals(vertexEdgeId);
            }
        };
    }

    public Vertex findOutVertex(Vertex termMention, Authorizations authorizations) {
        Authorizations authorizationsWithTermMention = getAuthorizations(authorizations);
        return singleOrDefault(
                termMention.getVertices(
                        Direction.IN,
                        VisalloProperties.TERM_MENTION_LABEL_HAS_TERM_MENTION,
                        authorizationsWithTermMention
                ),
                null
        );
    }

    public Authorizations getAuthorizations(Authorizations authorizations) {
        return graph.createAuthorizations(authorizations, VISIBILITY_STRING);
    }

    public void addJustification(
            Vertex vertex,
            String justificationText,
            ClientApiSourceInfo sourceInfo,
            VisalloVisibility visalloVisibility,
            Authorizations authorizations
    ) {
        if (justificationText != null) {
            PropertyJustificationMetadata propertyJustificationMetadata = new PropertyJustificationMetadata(
                    justificationText
            );
            removeSourceInfoEdgeFromVertex(
                    vertex.getId(),
                    vertex.getId(),
                    null,
                    null,
                    visalloVisibility,
                    authorizations
            );
            VisalloProperties.JUSTIFICATION.setProperty(
                    vertex,
                    propertyJustificationMetadata,
                    visalloVisibility.getVisibility(),
                    authorizations
            );
        } else if (sourceInfo != null) {
            Vertex outVertex = graph.getVertex(sourceInfo.vertexId, authorizations);
            VisalloProperties.JUSTIFICATION.removeProperty(vertex, authorizations);
            addSourceInfoToVertex(
                    vertex,
                    sourceInfo.vertexId,
                    TermMentionFor.VERTEX,
                    null,
                    null,
                    null,
                    sourceInfo.snippet,
                    sourceInfo.textPropertyKey,
                    sourceInfo.textPropertyName,
                    sourceInfo.startOffset,
                    sourceInfo.endOffset,
                    outVertex,
                    visalloVisibility.getVisibility(),
                    authorizations
            );
        }
    }

    public <T extends Element> void addSourceInfo(
            T element,
            String forElementId,
            TermMentionFor forType,
            String propertyKey,
            String propertyName,
            Visibility propertyVisibility,
            String snippet,
            String textPropertyKey,
            String textPropertyName,
            long startOffset,
            long endOffset,
            Vertex outVertex,
            Visibility visibility,
            Authorizations authorizations
    ) {
        if (element instanceof Vertex) {
            addSourceInfoToVertex(
                    (Vertex) element,
                    forElementId,
                    forType,
                    propertyKey,
                    propertyName,
                    propertyVisibility,
                    snippet,
                    textPropertyKey,
                    textPropertyName,
                    startOffset,
                    endOffset,
                    outVertex,
                    visibility,
                    authorizations
            );
        } else {
            addSourceInfoEdgeToEdge(
                    (Edge) element,
                    forElementId,
                    forType,
                    propertyKey,
                    propertyName,
                    propertyVisibility,
                    snippet,
                    textPropertyKey,
                    textPropertyName,
                    startOffset,
                    endOffset,
                    outVertex,
                    visibility,
                    authorizations
            );
        }
    }

    public void addSourceInfoToVertex(
            Vertex vertex,
            String forElementId,
            TermMentionFor forType,
            String propertyKey,
            String propertyName,
            Visibility propertyVisibility,
            String snippet,
            String textPropertyKey,
            String textPropertyName,
            long startOffset,
            long endOffset,
            Vertex outVertex,
            Visibility visibility,
            Authorizations authorizations
    ) {
        addSourceInfoToVertex(
            vertex,
            forElementId,
            forType,
            propertyKey,
            propertyName,
            propertyVisibility,
            null,
            snippet,
            textPropertyKey,
            textPropertyName,
            startOffset,
            endOffset,
            outVertex,
            visibility,
            authorizations
        );
    }

    public void addSourceInfoToVertex(
            Vertex vertex,
            String forElementId,
            TermMentionFor forType,
            String propertyKey,
            String propertyName,
            Visibility propertyVisibility,
            String edgeId,
            String snippet,
            String textPropertyKey,
            String textPropertyName,
            long startOffset,
            long endOffset,
            Vertex outVertex,
            Visibility visibility,
            Authorizations authorizations
    ) {
        visibility = VisalloVisibility.and(visibility, VISIBILITY_STRING);
        String termMentionVertexId = vertex.getId() + "hasSource" + outVertex.getId();
        if (propertyKey != null) {
            termMentionVertexId += ":" + propertyKey;
        }
        if (propertyName != null) {
            termMentionVertexId += ":" + propertyName;
        }
        if (propertyVisibility != null) {
            termMentionVertexId += ":" + propertyVisibility;
        }
        if (edgeId != null) {
            termMentionVertexId += ":" + edgeId;
        }
        VertexBuilder m = graph.prepareVertex(termMentionVertexId, visibility);
        VisalloProperties.TERM_MENTION_FOR_ELEMENT_ID.setProperty(m, forElementId, visibility);
        VisalloProperties.TERM_MENTION_FOR_TYPE.setProperty(m, forType, visibility);
        if (propertyKey != null) {
            VisalloProperties.TERM_MENTION_REF_PROPERTY_KEY.setProperty(m, propertyKey, visibility);
        }
        if (propertyName != null) {
            VisalloProperties.TERM_MENTION_REF_PROPERTY_NAME.setProperty(m, propertyName, visibility);
        }
        if (propertyVisibility != null) {
            VisalloProperties.TERM_MENTION_REF_PROPERTY_VISIBILITY.setProperty(
                    m,
                    propertyVisibility.getVisibilityString(),
                    visibility
            );
        }
        VisalloProperties.TERM_MENTION_SNIPPET.setProperty(m, snippet, visibility);
        VisalloProperties.TERM_MENTION_PROPERTY_KEY.setProperty(m, textPropertyKey, visibility);
        if (textPropertyName == null) {
            LOGGER.warn("not providing a property name for a term mention is deprecate");
        } else {
            VisalloProperties.TERM_MENTION_PROPERTY_NAME.setProperty(m, textPropertyName, visibility);
        }
        VisalloProperties.TERM_MENTION_START_OFFSET.setProperty(m, startOffset, visibility);
        VisalloProperties.TERM_MENTION_END_OFFSET.setProperty(m, endOffset, visibility);
        Vertex termMention = m.save(authorizations);

        graph.addEdge(
                VisalloProperties.TERM_MENTION_LABEL_HAS_TERM_MENTION + termMentionVertexId,
                outVertex,
                termMention,
                VisalloProperties.TERM_MENTION_LABEL_HAS_TERM_MENTION,
                visibility,
                authorizations
        );
        graph.addEdge(
                VisalloProperties.TERM_MENTION_LABEL_RESOLVED_TO + termMentionVertexId,
                termMention,
                vertex,
                VisalloProperties.TERM_MENTION_LABEL_RESOLVED_TO,
                visibility,
                authorizations
        );

        graph.flush();
        LOGGER.debug("added source info: %s", termMention.getId());
    }

    public void addSourceInfoEdgeToEdge(
            Edge edge,
            String forElementId,
            TermMentionFor forType,
            String propertyKey,
            String propertyName,
            Visibility propertyVisibility,
            String snippet,
            String textPropertyKey,
            String textPropertyName,
            long startOffset,
            long endOffset,
            Vertex originalVertex,
            Visibility visibility,
            Authorizations authorizations
    ) {
        Vertex inVertex = edge.getVertex(Direction.IN, authorizations);
        addSourceInfoToVertex(
                inVertex,
                forElementId,
                forType,
                propertyKey,
                propertyName,
                propertyVisibility,
                edge.getId(),
                snippet,
                textPropertyKey,
                textPropertyName,
                startOffset,
                endOffset,
                originalVertex,
                visibility,
                authorizations
        );
    }

    public void removeSourceInfoEdge(
            Element element,
            String propertyKey,
            String propertyName,
            VisalloVisibility visalloVisibility,
            Authorizations authorizations
    ) {
        if (element instanceof Vertex) {
            removeSourceInfoEdgeFromVertex(
                    element.getId(),
                    element.getId(),
                    propertyKey,
                    propertyName,
                    visalloVisibility,
                    authorizations
            );
        } else {
            removeSourceInfoEdgeFromEdge((Edge) element, propertyKey, propertyName, visalloVisibility, authorizations);
        }
    }

    public void removeSourceInfoEdgeFromVertex(
            String vertexId,
            String sourceInfoElementId,
            String propertyKey,
            String propertyName,
            VisalloVisibility visalloVisibility,
            Authorizations authorizations
    ) {
        Vertex termMention = findTermMention(
                vertexId,
                sourceInfoElementId,
                propertyKey,
                propertyName,
                visalloVisibility.getVisibility(),
                authorizations
        );
        if (termMention != null) {
            graph.softDeleteVertex(termMention, authorizations);
        }
    }

    public void removeSourceInfoEdgeFromEdge(
            Edge edge,
            String propertyKey,
            String propertyName,
            VisalloVisibility visalloVisibility,
            Authorizations authorizations
    ) {
        String inVertexId = edge.getVertexId(Direction.IN);
        removeSourceInfoEdgeFromVertex(
                inVertexId,
                edge.getId(),
                propertyKey,
                propertyName,
                visalloVisibility,
                authorizations
        );
    }

    private Vertex findTermMention(
            String vertexId,
            String forElementId,
            String propertyKey,
            String propertyName,
            Visibility propertyVisibility,
            Authorizations authorizations
    ) {
        Authorizations authorizationsWithTermMentions = getAuthorizations(authorizations);
        Vertex vertex = graph.getVertex(vertexId, authorizationsWithTermMentions);

        if (vertex == null) {
            return null;
        }

        List<Vertex> termMentions = IterableUtils.toList(vertex.getVertices(
                Direction.IN,
                VisalloProperties.TERM_MENTION_LABEL_RESOLVED_TO,
                authorizationsWithTermMentions
        ));
        for (Vertex termMention : termMentions) {
            if (forElementId != null && !forElementId.equals(
                    VisalloProperties.TERM_MENTION_FOR_ELEMENT_ID.getPropertyValue(termMention))) {
                continue;
            }
            if (propertyKey != null && !propertyKey.equals(
                    VisalloProperties.TERM_MENTION_REF_PROPERTY_KEY.getPropertyValue(termMention))) {
                continue;
            }
            if (propertyName != null && !propertyName.equals(
                    VisalloProperties.TERM_MENTION_REF_PROPERTY_NAME.getPropertyValue(termMention))) {
                continue;
            }
            if (propertyVisibility != null && !propertyVisibility.toString().equals(
                    VisalloProperties.TERM_MENTION_REF_PROPERTY_VISIBILITY.getPropertyValue(termMention))) {
                continue;
            }
            return termMention;
        }
        return null;
    }

    public ClientApiSourceInfo getSourceInfoForEdge(Edge edge, Authorizations authorizations) {
        String inVertexId = edge.getVertexId(Direction.IN);
        Vertex termMention = findTermMention(inVertexId, edge.getId(), null, null, null, authorizations);
        return getSourceInfoFromTermMention(termMention, authorizations);
    }

    public ClientApiSourceInfo getSourceInfoForVertex(Vertex vertex, Authorizations authorizations) {
        Vertex termMention = findTermMention(vertex.getId(), vertex.getId(), null, null, null, authorizations);
        return getSourceInfoFromTermMention(termMention, authorizations);
    }

    public ClientApiSourceInfo getSourceInfoForEdgeProperty(
            Edge edge,
            Property property,
            Authorizations authorizations
    ) {
        String inVertexId = edge.getVertexId(Direction.IN);
        Vertex termMention = findTermMention(
                inVertexId,
                edge.getId(),
                property.getKey(),
                property.getName(),
                property.getVisibility(),
                authorizations
        );
        return getSourceInfoFromTermMention(termMention, authorizations);
    }

    public ClientApiSourceInfo getSourceInfoForVertexProperty(
            String vertexId,
            Property property,
            Authorizations authorizations
    ) {
        Vertex termMention = findTermMention(
                vertexId,
                vertexId,
                property.getKey(),
                property.getName(),
                property.getVisibility(),
                authorizations
        );
        return getSourceInfoFromTermMention(termMention, authorizations);
    }

    private ClientApiSourceInfo getSourceInfoFromTermMention(Vertex termMention, Authorizations authorizations) {
        if (termMention == null) {
            return null;
        }
        Authorizations authorizationsWithTermMention = getAuthorizations(authorizations);
        ClientApiSourceInfo result = new ClientApiSourceInfo();
        result.vertexId = single(termMention.getVertexIds(
                Direction.IN,
                VisalloProperties.TERM_MENTION_LABEL_HAS_TERM_MENTION,
                authorizationsWithTermMention
        ));
        result.textPropertyKey = VisalloProperties.TERM_MENTION_PROPERTY_KEY.getPropertyValue(termMention);
        result.textPropertyName = VisalloProperties.TERM_MENTION_PROPERTY_NAME.getPropertyValue(termMention);
        result.startOffset = VisalloProperties.TERM_MENTION_START_OFFSET.getPropertyValue(termMention);
        result.endOffset = VisalloProperties.TERM_MENTION_END_OFFSET.getPropertyValue(termMention);
        result.snippet = SourceInfoSnippetSanitizer.sanitizeSnippet(
                VisalloProperties.TERM_MENTION_SNIPPET.getPropertyValue(termMention)
        );
        return result;
    }

    public ClientApiTermMentionsResponse toClientApi(
            Iterable<Vertex> termMentions,
            String workspaceId,
            Authorizations authorizations
    ) {
        authorizations = getAuthorizations(authorizations);
        ClientApiTermMentionsResponse termMentionsResponse = new ClientApiTermMentionsResponse();
        termMentionsResponse.getTermMentions().addAll(
                ClientApiConverter.toClientApi(termMentions, workspaceId, true, authorizations)
        );
        return termMentionsResponse;
    }
}
