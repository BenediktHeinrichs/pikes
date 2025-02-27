package eu.fbk.dkm.pikes.resources;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;

import eu.fbk.rdfpro.util.Statements;
import eu.fbk.utils.svm.Util;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ixa.kaflib.Coref;
import ixa.kaflib.Dep;
import ixa.kaflib.Entity;
import ixa.kaflib.ExternalRef;
import ixa.kaflib.KAFDocument;
import ixa.kaflib.LinkedEntity;
import ixa.kaflib.Opinion;
import ixa.kaflib.Opinion.OpinionExpression;
import ixa.kaflib.Opinion.OpinionHolder;
import ixa.kaflib.Opinion.OpinionTarget;
import ixa.kaflib.Predicate;
import ixa.kaflib.Predicate.Role;
import ixa.kaflib.Span;
import ixa.kaflib.Term;
import ixa.kaflib.Timex3;
import ixa.kaflib.WF;

/**
 * A filter for the post-processing of a NAF document.
 * <p>
 * The filter, configured and created using the builder pattern (see {@link #builder()}), performs
 * several optional and configurable operations on a {@code NAFDocumant} that is modified in
 * place. For the operations supported please refer to the javadoc of {@code Builder}.
 * <p>
 * This class is thread-safe.
 * </p>
 */
public final class NAFFilter implements Consumer<KAFDocument> {

    public static final String SUMO_NAMESPACE = "http://www.ontologyportal.org/SUMO.owl#";

    public static final IRI SUMO_PROCESS = SimpleValueFactory.getInstance()
            .createIRI(SUMO_NAMESPACE, "Process");
    // public static final IRI SUMO_PROCESS =
    // SimpleValueFactory.getInstance().createIRI(SUMO_NAMESPACE, "Process");

    private static final Logger LOGGER = LoggerFactory.getLogger(NAFFilter.class);

    private static final Map<String, String> ENTITY_SST_TO_TYPES = ImmutableMap
            .<String, String>builder().put("person", "PER").put("group", "ORG")
            .put("location", "LOC").put("quantity", "QUANTITY").put("artifact", "PRODUCT")
            .put("act", "EVENT").put("event", "EVENT").put("phenomenon", "EVENT")
            .put("process", "EVENT").put("state", "EVENT").put("animal", "MISC")
            .put("plant", "MISC").put("body", "MISC").put("shape", "MISC").put("motive", "MISC")
            .put("object", "MISC").put("substance", "MISC").build();
    // .put("cognition", "EVENT")

    private static final Pattern SRL_ROLE_PATTERN = Pattern.compile("A(\\d).*");

    private static final String PARTICIPATION_REGEX = ""
            + "SUB? (COORD CONJ?)* (PMOD (COORD CONJ?)*)? ((VC OPRD?)|(IM OPRD?))*";

    private static final String[] LINKING_STOP_WORDS;

    private static final BiMap<String, String> MAPPING_PREFIXES = ImmutableBiMap.of("propbank",
            "pb", "nombank", "nb", "verbnet", "vn", "framenet", "fn");

    private static final Multimap<String, String> MAPPING_PREDICATES;

    private static final Multimap<String, String> MAPPING_ARGUMENTS;

    public static final NAFFilter DEFAULT = NAFFilter.builder().build();

    static {
        List<String> stopwords = Collections.emptyList();
        try {
            stopwords = Resources.readLines(NAFFilter.class.getResource("linking_stopwords"),
                    Charsets.UTF_8);
            LOGGER.info("Loaded {} linking stopwords", stopwords.size());
        } catch (final IOException ex) {
            LOGGER.error("Could not load linking stopwords", ex);
        }
        LINKING_STOP_WORDS = stopwords.toArray(new String[stopwords.size()]);
        for (int i = 0; i < LINKING_STOP_WORDS.length; ++i) {
            LINKING_STOP_WORDS[i] = LINKING_STOP_WORDS[i].toLowerCase();
        }
        Arrays.sort(LINKING_STOP_WORDS);

        MAPPING_PREDICATES = HashMultimap.create();
        MAPPING_ARGUMENTS = HashMultimap.create();
        try {
            for (final String line : Resources.readLines(
                    NAFFilter.class.getResource("mappings-frames.tsv"), Charsets.UTF_8)) {
                final List<String> tokens = Splitter.on("\t").trimResults().splitToList(line);
                final String prefix = tokens.get(0).substring(0, 2).toLowerCase();
                final String fromKey = prefix + ":" + tokens.get(1);
                final String toKey = "fn:" + Character.toUpperCase(tokens.get(2).charAt(0))
                        + tokens.get(2).substring(1);
                MAPPING_PREDICATES.put(fromKey, toKey);
            }
            for (final String line : Resources.readLines(
                    NAFFilter.class.getResource("mappings-roles.tsv"), Charsets.UTF_8)) {
                final List<String> tokens = Splitter.on("\t").trimResults().splitToList(line);
                final String prefix = tokens.get(0).substring(0, 2).toLowerCase();
                final String fromKey = prefix + ":" + tokens.get(1);
                final String fnRole = tokens.get(2);
                final int index = fnRole.indexOf('@');
                final String toKey = "fn:" + Character.toUpperCase(fnRole.charAt(0))
                        + fnRole.substring(1, index + 1)
                        + Character.toUpperCase(fnRole.charAt(index + 1))
                        + fnRole.substring(index + 2);
                MAPPING_ARGUMENTS.put(fromKey, toKey);
            }

        } catch (final Throwable ex) {
            LOGGER.error("Could not load mappings", ex);
        }
    }

    private final boolean termSenseFiltering;

    private final boolean termSenseCompletion;

    private final boolean entityRemoveOverlaps;

    private final boolean entitySpanFixing;

    private final boolean entityAddition;

    private final boolean entityValueNormalization;

    private final boolean linkingCompletion;

    private final boolean linkingFixing;

    private final boolean corefForRoleDependencies;

    private final boolean corefSpanFixing;

    private final boolean srlPreprocess;

    private final boolean srlEnableMate;

    private final boolean srlEnableSemafor;

    private final boolean srlRemoveWrongRefs;

    private final boolean srlRemoveUnknownPredicates;

    private final boolean srlPredicateAddition;

    private final boolean srlSelfArgFixing;

    private final boolean srlSenseMapping;

    private final boolean srlSenseMappingPM;

    private final boolean srlFrameBaseMapping;

    private final boolean srlRoleLinking;

    private final boolean srlRoleLinkingUsingCoref;

    private final boolean srlPreMOnIRIs;

    private final boolean opinionLinking;

    private final boolean opinionLinkingUsingCoref;

    private NAFFilter(final Builder builder) {
        this.termSenseFiltering = MoreObjects.firstNonNull(builder.termSenseFiltering, true);
        this.termSenseCompletion = MoreObjects.firstNonNull(builder.termSenseCompletion, true);
        this.entityRemoveOverlaps = MoreObjects.firstNonNull(builder.entityRemoveOverlaps, true);
        this.entitySpanFixing = MoreObjects.firstNonNull(builder.entitySpanFixing, true);
        this.entityAddition = MoreObjects.firstNonNull(builder.entityAddition, true);
        this.entityValueNormalization = MoreObjects.firstNonNull(builder.entityValueNormalization,
                true);
        this.linkingCompletion = MoreObjects.firstNonNull(builder.linkingCompletion, true);
        this.linkingFixing = MoreObjects.firstNonNull(builder.linkingFixing, false);
        this.corefForRoleDependencies = MoreObjects.firstNonNull(builder.corefForRoleDependencies,
                false);
        this.corefSpanFixing = MoreObjects.firstNonNull(builder.corefSpanFixing, false);
        this.srlPreprocess = MoreObjects.firstNonNull(builder.srlPreprocess, true);
        this.srlEnableMate = MoreObjects.firstNonNull(builder.srlEnableMate, true);
        this.srlEnableSemafor = MoreObjects.firstNonNull(builder.srlEnableSemafor, true);
        this.srlRemoveWrongRefs = MoreObjects.firstNonNull(builder.srlRemoveWrongRefs, true);
        this.srlRemoveUnknownPredicates = MoreObjects
                .firstNonNull(builder.srlRemoveUnknownPredicates, false);
        this.srlPredicateAddition = MoreObjects.firstNonNull(builder.srlPredicateAddition, true);
        this.srlSelfArgFixing = MoreObjects.firstNonNull(builder.srlSelfArgFixing, true);
        this.srlSenseMapping = MoreObjects.firstNonNull(builder.srlSenseMapping, true);
        this.srlSenseMappingPM = false; // TODO disabled
        this.srlFrameBaseMapping = MoreObjects.firstNonNull(builder.srlFrameBaseMapping, true);
        this.srlRoleLinking = MoreObjects.firstNonNull(builder.srlRoleLinking, true);
        this.srlRoleLinkingUsingCoref = MoreObjects.firstNonNull(builder.srlRoleLinkingUsingCoref,
                true);

        this.srlPreMOnIRIs = MoreObjects.firstNonNull(builder.srlPreMOnIRIs, true);
        this.opinionLinking = MoreObjects.firstNonNull(builder.opinionLinking, true);
        this.opinionLinkingUsingCoref = MoreObjects.firstNonNull(builder.opinionLinkingUsingCoref,
                true);
    }

    @Override
    public void accept(final KAFDocument document) {
        filter(document);
    }

    /**
     * Filters the NAF document specified (the document is modified in-place). Filtering is
     * controlled by the flags specified when creating the {@code NAFFilter} object.
     *
     * @param document
     *            the document to filter
     */
    public void filter(final KAFDocument document) {

        // Check arguments
        Preconditions.checkNotNull(document);

        // Log beginning of operation
        final long ts = System.currentTimeMillis();
        LOGGER.debug("== Filtering {} ==", document.getPublic().uri);

        // Normalize the document
        NAFUtils.normalize(document);

        // Term-level filtering
        if (this.termSenseFiltering) {
            applyTermSenseFiltering(document);
        }
        if (this.termSenseCompletion) {
            applyTermSenseCompletion(document);
        }

        // Entity-level / Linking filtering
        if (this.entityRemoveOverlaps) {
            applyEntityRemoveOverlaps(document);
        }
        if (this.entitySpanFixing) {
            applyEntitySpanFixing(document);
        }
        if (this.linkingCompletion) {
            applyLinkingCompletion(document);
        }
        if (this.linkingFixing) {
            applyLinkingFixing(document);
        }
        if (this.entityAddition) {
            applyEntityAddition(document);
        }
        if (this.entityValueNormalization) {
            applyEntityValueNormalization(document);
        }

        // SRL-level filtering
        if (this.srlPreprocess) {
            applySRLPreprocess(document);
        }
        if (this.srlRemoveWrongRefs) {
            applySRLRemoveWrongRefs(document);
        }
        if (this.srlRemoveUnknownPredicates) {
            applySRLRemoveUnknownPredicates(document);
        }
        if (this.srlPredicateAddition) {
            applySRLPredicateAddition(document);
        }
        if (this.srlSelfArgFixing) {
            applySRLSelfArgFixing(document);
        }
        if (this.srlSenseMapping) {
            applySRLSenseMapping(document);
        }
        if (this.srlFrameBaseMapping) {
            applySRLFrameBaseMapping(document);
        }
        if (this.srlRoleLinking) {
            applySRLRoleLinking(document);
        }

        // added for replacing with premon IRIs
        if (this.srlPreMOnIRIs) {
            applySRLPreMOnIRIs(document);
        }

        // Coref-level filtering
        if (this.corefForRoleDependencies) {
            applyCorefForRoleDependencies(document);
        }
        if (this.corefSpanFixing) {
            applyCorefSpanFixing(document);
        }

        // Opinion-level filtering
        if (this.opinionLinking) {
            applyOpinionLinking(document);
        }

        LOGGER.debug("Done in {} ms", System.currentTimeMillis() - ts);
    }

    // private void applyEntityTypeFixing(final KAFDocument document) {
    //
    // for (final Entity entity : ImmutableList.copyOf(document.getEntities())) {
    //
    //
    //
    //
    // // Remove initial determiners and prepositions, plus all the terms not containing at
    // // least a letter or a digit. Move to next entity if no change was applied
    // final List<Term> filteredTerms = NAFUtils.filterTerms(entity.getTerms());
    // if (filteredTerms.size() == entity.getTerms().size()) {
    // continue;
    // }
    //
    // // Remove the old entity
    // document.removeAnnotation(entity);
    //
    // // If some term remained, add the filtered entity, reusing old type, named flag and
    // // external references
    // Entity newEntity = null;
    // if (!filteredTerms.isEmpty()) {
    // newEntity = document.newEntity(ImmutableList.of(KAFDocument
    // .newTermSpan(filteredTerms)));
    // newEntity.setType(entity.getType());
    // newEntity.setNamed(entity.isNamed());
    // for (final ExternalRef ref : entity.getExternalRefs()) {
    // newEntity.addExternalRef(ref);
    // }
    // }
    //
    // // Log the change
    // if (LOGGER.isDebugEnabled()) {
    // LOGGER.debug((newEntity == null ? "Removed" : "Replaced") + " invalid " //
    // + NAFUtils.toString(entity) + (newEntity == null ? "" : " with filtered " //
    // + NAFUtils.toString(newEntity)));
    // }
    // }
    //
    // }

    private void applyTermSenseFiltering(final KAFDocument document) {

        for (final Term term : document.getTerms()) {
            if (term.getMorphofeat() != null && term.getMorphofeat().startsWith("NNP")) {
                NAFUtils.removeRefs(term, NAFUtils.RESOURCE_WN_SYNSET, null);
                NAFUtils.removeRefs(term, NAFUtils.RESOURCE_WN_SST, null);
                NAFUtils.removeRefs(term, NAFUtils.RESOURCE_BBN, null);
                NAFUtils.removeRefs(term, NAFUtils.RESOURCE_SUMO, null);
                NAFUtils.removeRefs(term, NAFUtils.RESOURCE_YAGO, null);
            }
        }
    }

    private void applyTermSenseCompletion(final KAFDocument document) {

        for (final Term term : document.getTerms()) {

            // Retrieve existing refs
            ExternalRef bbnRef = NAFUtils.getRef(term, NAFUtils.RESOURCE_BBN, null);
            ExternalRef synsetRef = NAFUtils.getRef(term, NAFUtils.RESOURCE_WN_SYNSET, null);
            ExternalRef sstRef = NAFUtils.getRef(term, NAFUtils.RESOURCE_WN_SST, null);
            final List<ExternalRef> sumoRefs = NAFUtils.getRefs(term, NAFUtils.RESOURCE_SUMO,
                    null);
            final List<ExternalRef> yagoRefs = NAFUtils.getRefs(term, NAFUtils.RESOURCE_YAGO,
                    null);

            // Retrieve a missing SST from the WN Synset (works always)
            if (sstRef == null && synsetRef != null) {
                final String sst = WordNet.mapSynsetToSST(synsetRef.getReference());
                if (sstRef == null || !Objects.equal(sstRef.getReference(), sst)) {
                    LOGGER.debug((sstRef == null ? "Added" : "Overridden") + " SST '" + sst
                            + "' of " + NAFUtils.toString(term) + " based on Synset '"
                            + synsetRef.getReference() + "'");
                    sstRef = document.newExternalRef(NAFUtils.RESOURCE_WN_SST, sst);
                    NAFUtils.addRef(term, sstRef);
                }
            }

            // Apply noun-based mapping.
            final boolean isNoun = Character.toUpperCase(term.getPos().charAt(0)) == 'N';
            if (isNoun) {

                // Retrieve a missing BBN from the WN Synset
                if (bbnRef == null && synsetRef != null) {
                    final String bbn = WordNet.mapSynsetToBBN(synsetRef.getReference());
                    if (bbn != null) {
                        bbnRef = document.newExternalRef(NAFUtils.RESOURCE_BBN, bbn);
                        NAFUtils.addRef(term, bbnRef);
                        LOGGER.debug("Added BBN '" + bbn + "' of " + NAFUtils.toString(term)
                                + " based on Synset '" + synsetRef.getReference() + "'");
                    }

                }

                // Retrieve a missing WN Synset from the BBN
                if (synsetRef == null && bbnRef != null) {
                    final String synsetID = WordNet.mapBBNToSynset(bbnRef.getReference());
                    if (synsetID != null) {
                        synsetRef = document.newExternalRef(NAFUtils.RESOURCE_WN_SYNSET, synsetID);
                        NAFUtils.addRef(term, synsetRef);
                        LOGGER.debug(
                                "Added Synset '" + synsetID + "' of " + NAFUtils.toString(term)
                                        + " based on BBN '" + bbnRef.getReference() + "'");
                    }
                }

                // Retrieve a missing SST from the BBN
                if (sstRef == null && bbnRef != null) {
                    final String sst = WordNet.mapBBNToSST(bbnRef.getReference());
                    if (sst != null) {
                        sstRef = document.newExternalRef(NAFUtils.RESOURCE_WN_SST, sst);
                        NAFUtils.addRef(term, sstRef);
                        LOGGER.debug("Added SST '" + sst + "' of " + NAFUtils.toString(term)
                                + " based on BBN '" + bbnRef.getReference() + "'");
                    }
                }
            }

            // Apply mapping to SUMO if synset is available
            final String lemma = term.getLemma().toLowerCase();
            if (sumoRefs.isEmpty() && synsetRef != null && !lemma.equals("be")) {
                Set<String> synsetIDs = Sets.newHashSet(synsetRef.getReference());
                Set<IRI> conceptIRIs = Sumo.synsetsToConcepts(synsetIDs);
                while (conceptIRIs.isEmpty() && !synsetIDs.isEmpty()) {
                    final Set<String> oldSynsetIDs = synsetIDs;
                    synsetIDs = Sets.newHashSet();
                    for (final String oldSynsetID : oldSynsetIDs) {
                        synsetIDs.addAll(WordNet.getHypernyms(oldSynsetID));
                    }
                    conceptIRIs = Sumo.synsetsToConcepts(synsetIDs);
                }
                if (conceptIRIs.isEmpty()) {
                    synsetIDs = WordNet.getHyponyms(synsetRef.getReference());
                    conceptIRIs = Sumo.synsetsToConcepts(synsetIDs);
                }
                if (!conceptIRIs.isEmpty()) {
                    for (final IRI conceptIRI : conceptIRIs) {
                        final String sumoID = conceptIRI.getLocalName();
                        final ExternalRef sumoRef = document.newExternalRef(NAFUtils.RESOURCE_SUMO,
                                sumoID);
                        NAFUtils.setRef(term, sumoRef);
                        LOGGER.debug("Added SUMO mapping: " + NAFUtils.toString(term) + " -> sumo:"
                                + conceptIRI.getLocalName());
                    }
                }
            }

            // Apply mapping to Yago if synset is available
            if (yagoRefs.isEmpty() && synsetRef != null) {
                for (final IRI uri : YagoTaxonomy
                        .getDBpediaYagoIRIs(ImmutableList.of(synsetRef.getReference()))) {
                    final String yagoID = uri.stringValue()
                            .substring(YagoTaxonomy.NAMESPACE.length());
                    final ExternalRef yagoRef = document.newExternalRef(NAFUtils.RESOURCE_YAGO,
                            yagoID);
                    NAFUtils.setRef(term, yagoRef);
                    LOGGER.debug("Added Yago mapping: " + NAFUtils.toString(term) + " -> yago:"
                            + yagoID);
                }
            }
        }
    }

    private void applyEntitySpanFixing(final KAFDocument document) {

        // Filter or remove entities consisting of invalid terms
        for (final Entity entity : ImmutableList.copyOf(document.getEntities())) {

            // Remove initial determiners and prepositions, plus all the terms not containing at
            // least a letter or a digit. Move to next entity if no change was applied
            final List<Term> filteredTerms = NAFUtils.filterTerms(entity.getTerms());
            if (filteredTerms.size() == entity.getTerms().size()) {
                continue;
            }

            // Remove the old entity
            document.removeAnnotation(entity);

            // If some term remained, add the filtered entity, reusing old type, named flag and
            // external references
            Entity newEntity = null;
            if (!filteredTerms.isEmpty()) {
                newEntity = document
                        .newEntity(ImmutableList.of(KAFDocument.newTermSpan(filteredTerms)));
                newEntity.setType(entity.getType());
                newEntity.setNamed(entity.isNamed());
                for (final ExternalRef ref : entity.getExternalRefs()) {
                    newEntity.addExternalRef(ref);
                }
            }

            // Log the change
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug((newEntity == null ? "Removed" : "Replaced") + " invalid " //
                        + NAFUtils.toString(entity) + (newEntity == null ? ""
                                : " with filtered " //
                                        + NAFUtils.toString(newEntity)));
            }
        }
    }

    private void applyEntityRemoveOverlaps(final KAFDocument document) {

        // Consider all the entities in the document
        outer: for (final Entity entity : ImmutableList.copyOf(document.getEntities())) {
            for (final Term term : entity.getTerms()) {

                // Remove entities whose span is contained in the span of another entity
                for (final Entity entity2 : document.getEntitiesByTerm(term)) {
                    if (entity2 != entity && entity2.getTerms().containsAll(entity.getTerms())) {
                        document.removeAnnotation(entity);
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Removed " + NAFUtils.toString(entity)
                                    + " overlapping with " + NAFUtils.toString(entity2));
                        }
                        continue outer;
                    }
                }

                // Remove entities whose span overlaps with the span of some timex
                for (final WF wf : term.getWFs()) {
                    final List<Timex3> timex = document.getTimeExsByWF(wf);
                    if (!timex.isEmpty()) {
                        document.removeAnnotation(entity);
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Removed " + NAFUtils.toString(entity)
                                    + " overlapping with TIMEX3 '" + NAFUtils.toString(timex));
                        }
                        continue outer;
                    }
                }
            }
        }
    }

    private void applyEntityAddition(final KAFDocument document) {

        for (final Term term : document.getTerms()) {

            // Select names, nouns and pronouns that are not part of NE or Timex
            final char pos = Character.toUpperCase(term.getPos().charAt(0));
            final Dep dep = document.getDepToTerm(term);
            final boolean namePart = pos == 'R' && dep != null
                    && dep.getRfunc().toLowerCase().contains("name")
                    && Character.toUpperCase(dep.getFrom().getPos().charAt(0)) == 'R'
                    && document.getEntitiesByTerm(dep.getFrom()).isEmpty();
            if (pos != 'R' && pos != 'N' && pos != 'Q' || namePart
                    || !document.getTimeExsByWF(term.getWFs().get(0)).isEmpty() //
                    || !document.getEntitiesByTerm(term).isEmpty()) {
                continue;
            }

            // Determine the entity type based on NER tag first, WN synset then and SST last
            String type = null;
            final ExternalRef bbnRef = NAFUtils.getRef(term, NAFUtils.RESOURCE_BBN, null);
            if (bbnRef != null) {
                type = bbnRef.getReference();
            } else {
                final ExternalRef synsetRef = NAFUtils.getRef(term, NAFUtils.RESOURCE_WN_SYNSET,
                        null);
                if (synsetRef != null) {
                    type = WordNet.mapSynsetToBBN(synsetRef.getReference());
                } else {
                    final ExternalRef sstRef = NAFUtils.getRef(term, NAFUtils.RESOURCE_WN_SST,
                            null);
                    if (sstRef != null) {
                        String sst = sstRef.getReference();
                        sst = sst.substring(sst.lastIndexOf('.') + 1);
                        type = ENTITY_SST_TO_TYPES.get(sst);
                    }
                }
            }

            // Determine the terms for the nominal node.
            // TODO: consider multiwords
            final Span<Term> span = NAFUtils.getNominalSpan(document, term, false, false);

            // Add the entity, setting its type and 'named' flag
            final Entity entity = document.newEntity(ImmutableList.of(span));
            if (type != null)
                entity.setType(type.toUpperCase().replace("PERSON", "PER")
                        .replace("ORGANIZATION", "ORG").replace("LOCATION", "LOC"));
            entity.setNamed(pos == 'R');
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Added " + (entity.isNamed() ? "named " : "")
                        + NAFUtils.toString(entity) + " with type '" + type + "'");
            }
        }
    }

    private void applyEntityValueNormalization(final KAFDocument document) {

        for (final Entity entity : document.getEntities()) {
            String type = entity.getType();
            type = type == null ? null : type.toLowerCase();
            if ("cardinal".equals(type) || "ordinal".equals(type) || "percent".equals(type)
                    || "money".equals(type)) {

                ExternalRef ref = null;
                final String str = entity.getSpans().get(0).getStr().toLowerCase();
                Double value = null;
                try {
                    value = NumberSpeller.parse(str);
                } catch (Throwable ex) {
                    LOGGER.debug("Could not parse number '" + str + "'", ex);
                }
                if (value != null) {
                    String prefix = "";
                    if ("percent".equals(type)) {
                        prefix = "%";
                    } else if ("money".equals(type)) {
                        prefix = "¤";
                        if (str.contains("euro")) {
                            prefix = "€";
                        } else if (str.contains("dollar")) {
                            prefix = "$";
                        } else if (str.contains("yen")) {
                            prefix = "¥";
                        }
                    }
                    ref = document.newExternalRef(NAFUtils.RESOURCE_VALUE,
                            prefix + Double.toString(value.doubleValue()));
                }

                if (ref != null && NAFUtils.getRef(entity, ref.getResource(), null) == null) {
                    NAFUtils.addRef(entity, ref);
                    LOGGER.debug("Added ref '" + ref + "' to " + NAFUtils.toString(entity));
                }
            }
        }
    }

    private void applyLinkingCompletion(final KAFDocument document) {

        for (final LinkedEntity le : document.getLinkedEntities()) {

            // Determine head for current linked entity
            final List<Term> terms = document.getTermsByWFs(le.getWFs().getTargets());
            final Term head = document.getTermsHead(terms);
            if (head == null) {
                continue;
            }

            // Apply the sense to entities with same head where it is missing
            Entity entityToModify = null;
            for (final Entity entity : document.getEntitiesByTerm(head)) {
                if (head.equals(document.getTermsHead(entity.getTerms()))) {
                    entityToModify = entity;
                }
            }
            if (entityToModify == null) {
                final Span<Term> span = KAFDocument
                        .newTermSpan(document.getTermsByWFs(le.getWFs().getTargets()));
                boolean overlap = false;
                for (final Term term : span.getTargets()) {
                    final List<Entity> overlappingEntities = document.getEntitiesByTerm(term);
                    if (overlappingEntities != null && !overlappingEntities.isEmpty()) {
                        overlap = true;
                        break;
                    }
                }
                if (!overlap) {
                    final boolean named = head.getMorphofeat().startsWith("NNP");
                    boolean accept = named;
                    if (!accept) {
                        final String textStr = span.getStr().toLowerCase().replaceAll("\\s+", "_");
                        final String entityStr = Statements.VALUE_FACTORY
                                .createIRI(le.getReference()).getLocalName().toLowerCase();
                        accept = textStr.equals(entityStr);
                    }
                    if (accept) {
                        entityToModify = document.newEntity(ImmutableList.of(span));
                        entityToModify.setNamed(head.getMorphofeat().startsWith("NNP"));
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug(
                                    "Added linked " + (entityToModify.isNamed() ? "named " : "")
                                            + NAFUtils.toString(entityToModify));
                        }
                    }
                }
            }

            if (entityToModify != null) {
                final ExternalRef existingRef = NAFUtils.getRef(entityToModify, le.getResource(),
                        le.getReference());
                if (existingRef == null) {
                    final ExternalRef ref = document.newExternalRef(le.getResource(),
                            le.getReference());
                    ref.setConfidence((float) le.getConfidence());
                    NAFUtils.addRef(entityToModify, ref);
                    LOGGER.debug(
                            "Added ref '" + ref + "' to " + NAFUtils.toString(entityToModify));
                } else {
                    float existingRefConfidence = existingRef.getConfidence();
                    if (existingRefConfidence < le.getConfidence()) {
                        existingRef.setConfidence((float) le.getConfidence());
                        LOGGER.debug("Modified confidence of '" + existingRef + "' to "
                                + le.getConfidence());
                    }
                }
            }

            // Apply the sense to predicates with same head where it is missing
            for (final Predicate predicate : document.getPredicatesByTerm(head)) {
                if (head.equals(document.getTermsHead(predicate.getTerms()))) {
                    if (NAFUtils.getRef(predicate, le.getResource(), le.getReference()) == null) {
                        final ExternalRef ref = document.newExternalRef(le.getResource(),
                                le.getReference());
                        ref.setConfidence((float) le.getConfidence());
                        NAFUtils.addRef(predicate, ref);
                        LOGGER.debug("Added ref '" + ref + "' to " + NAFUtils.toString(predicate));
                    }
                }
            }
        }
    }

    private void applyLinkingFixing(final KAFDocument document) {

        // Check each linked entity, dropping the links if the span is in the stop word list
        final List<ExternalRef> refs = Lists.newArrayList();
        for (final Entity entity : document.getEntities()) {

            // Extract all the <ExternalRef> elements with links for the current entity
            refs.clear();
            for (final ExternalRef ref : entity.getExternalRefs()) {
                if (!NAFUtils.RESOURCE_VALUE.equals(ref.getResource())) {
                    refs.add(ref);
                }
            }

            // If the entity is linked, check its span is not in the stop word list
            if (!refs.isEmpty()) {
                final String[] tokens = Util.hardTokenize(entity.getStr());
                final String normalized = Joiner.on(' ').join(tokens).toLowerCase();
                if (Arrays.binarySearch(LINKING_STOP_WORDS, normalized) >= 0) {
                    for (final ExternalRef ref : refs) {
                        NAFUtils.removeRefs(entity, ref.getResource(), ref.getReference());
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Removed stop-word ref '{}' from {}", ref,
                                    NAFUtils.toString(entity));
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void applyCorefSpanFixing(final KAFDocument document) {

        // Process each <coref> element in the NAF document
        for (final Coref coref : ImmutableList.copyOf(document.getCorefs())) {

            // Remove spans without valid head
            for (final Span<Term> span : ImmutableList.copyOf(coref.getSpans())) {
                final Term head = NAFUtils.extractHead(document, span);
                if (head == null) {
                    coref.getSpans().remove(span);
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Removed span with invalid head '{}' from {}", span.getStr(),
                                NAFUtils.toString(coref));
                    }
                } else {
                    span.setHead(head);
                }
            }

            // Remove spans containing smaller spans + determine if there is span with NNP head
            boolean hasProperNounHead = false;
            boolean isEvent = false;
            final List<Span<Term>> spans = ImmutableList.copyOf(coref.getSpans());
            outer: for (final Span<Term> span1 : spans) {
                for (final Span<Term> span2 : spans) {
                    if (span1.size() > span2.size()
                            && span1.getTargets().containsAll(span2.getTargets())) {
                        coref.getSpans().remove(span1);
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Removed span '{}' including smaller span '{}' from {}",
                                    span1.getStr(), span2.getStr(), NAFUtils.toString(coref));
                        }
                        continue outer;
                    }
                }
                hasProperNounHead |= span1.getHead().getMorphofeat().startsWith("NNP");
                if (!isEvent) {
                    for (final ExternalRef ref : NAFUtils.getRefs(span1.getHead(),
                            NAFUtils.RESOURCE_SUMO, null)) {
                        final IRI sumoID = Statements.VALUE_FACTORY
                                .createIRI(SUMO_NAMESPACE + ref.getReference());
                        if (Sumo.isSubClassOf(sumoID, SUMO_PROCESS)) {
                            isEvent = true;
                        }
                    }
                }
            }

            // Shrink spans containing a proper name, if head of another span is proper name
            if (hasProperNounHead) {

                // Drop spans not corresponding to non-role predicates
                for (final Span<Term> span : ImmutableList.copyOf(coref.getSpans())) {
                    final Term head = span.getHead();
                    if (!head.getMorphofeat().startsWith("NNP") && !isEvent) {
                        if (head.getMorphofeat().startsWith("VB")) {
                            coref.getSpans().remove(span);
                            LOGGER.debug("Removed span with VB head '{}' from {}", span.getStr(),
                                    NAFUtils.toString(coref));
                        } else {
                            outer: for (final Predicate predicate : document
                                    .getPredicatesByTerm(head)) {
                                for (final ExternalRef ref : NAFUtils.getRefs(predicate,
                                        NAFUtils.RESOURCE_NOMBANK, null)) {
                                    final NomBank.Roleset roleset = NomBank
                                            .getRoleset(ref.getReference());
                                    if (roleset != null
                                            && roleset.getPredMandatoryArgNums().isEmpty()
                                            && roleset.getPredOptionalArgNums().isEmpty()) {
                                        // Not a role
                                        coref.getSpans().remove(span);
                                        LOGGER.debug(
                                                "Removed span with non-role predicate "
                                                        + "head '{}' from {}",
                                                span.getStr(), NAFUtils.toString(coref));
                                        break outer;
                                    }
                                }
                            }
                        }
                    }
                }

            } else {

                // Split the coreference set into multiple sets, one for each sentence
                final Multimap<Integer, Span<Term>> spansBySentence = HashMultimap.create();
                for (final Span<Term> span : coref.getSpans()) {
                    final int sentID = span.getTargets().get(0).getSent();
                    spansBySentence.put(sentID, span);
                }
                if (spansBySentence.keySet().size() > 1) {
                    coref.getSpans().clear();
                    for (final Collection<Span<Term>> sentSpans : spansBySentence.asMap()
                            .values()) {
                        if (sentSpans.size() > 1) {
                            document.newCoref(Lists.newArrayList(sentSpans));
                        }
                    }
                }

            }

            // Drop coref in case no span remains.
            if (coref.getSpans().isEmpty()) {
                document.removeAnnotation(coref);
                LOGGER.debug("Removed empty coref set {}", NAFUtils.toString(coref));
            }
        }
    }

    private void applyCorefForRoleDependencies(final KAFDocument document) {

        outer: for (final Dep dep : document.getDeps()) {
            final String label = dep.getRfunc();
            if ("APPO".equals(label) || "TITLE".equals(label) || "NMOD".equals(label)) {

                // Identify the proper name term and the role name term
                Term nameTerm;
                Term roleTerm;
                final String posFrom = dep.getFrom().getMorphofeat();
                final String posTo = dep.getTo().getMorphofeat();
                if (posFrom.startsWith("NNP") && posTo.startsWith("NN")
                        && !posTo.startsWith("NNP")) {
                    nameTerm = dep.getFrom();
                    roleTerm = dep.getTo();
                } else if (posTo.startsWith("NNP") && posFrom.startsWith("NN")
                        && !posFrom.startsWith("NNP") && label.equals("APPO")) {
                    nameTerm = dep.getTo();
                    roleTerm = dep.getFrom();
                } else {
                    continue outer;
                }

                // Abort if the two terms are already marked as coreferential
                for (final Coref coref : document.getCorefsByTerm(nameTerm)) {
                    if (NAFUtils.hasHead(document, coref, nameTerm)
                            && NAFUtils.hasHead(document, coref, roleTerm)) {
                        continue outer;
                    }
                }

                // Verify the role term actually corresponds to a nombank role
                boolean isActualRole = false;
                predLoop: for (final Predicate predicate : document
                        .getPredicatesByTerm(roleTerm)) {
                    for (final ExternalRef ref : predicate.getExternalRefs()) {
                        if (NAFUtils.RESOURCE_NOMBANK.equals(ref.getResource())) {
                            final NomBank.Roleset rs = NomBank.getRoleset(ref.getReference());
                            if (rs != null && (!rs.getPredMandatoryArgNums().isEmpty() //
                                    || !rs.getPredOptionalArgNums().isEmpty())) {
                                isActualRole = true;
                                break predLoop;
                            }
                        }
                    }
                }
                if (!isActualRole) {
                    continue outer;
                }

                // Expand coordination
                final Set<Term> roleHeads = document
                        .getTermsByDepAncestors(ImmutableSet.of(roleTerm), "(COORD CONJ?)*");
                final Set<Term> nameHeads = document
                        .getTermsByDepAncestors(ImmutableSet.of(nameTerm), "(COORD CONJ?)*");

                // Check that all name heads are proper names
                for (final Term nameHead : nameHeads) {
                    if (!nameHead.getMorphofeat().startsWith("NNP")) {
                        continue outer;
                    }
                }

                // Check role plural/singular form
                for (final Term roleHead : roleHeads) {
                    final boolean plural = roleHead.getMorphofeat().endsWith("S");
                    if (nameHeads.size() == 1 && plural || nameHeads.size() > 1 && !plural) {
                        continue outer;
                    }
                }

                // Add a new coreference cluster
                final List<Span<Term>> spans = Lists.newArrayList();
                spans.add(NAFUtils.getNominalSpan(document, nameTerm, true, false));
                for (final Term roleHead : roleHeads) {
                    spans.add(NAFUtils.getNominalSpan(document, roleHead, false, false));
                }
                final Coref coref = document.newCoref(spans);
                if (LOGGER.isDebugEnabled()) {
                    final StringBuilder builder = new StringBuilder("Added coref ");
                    builder.append(coref.getId()).append(":");
                    for (final Span<Term> span : coref.getSpans()) {
                        builder.append(" '").append(span.getStr()).append('\'');
                    }
                    LOGGER.debug(builder.toString());
                }
            }
        }
    }

    private void applySRLPreprocess(final KAFDocument document) {

        // Allocate two maps to store term -> predicate pairs
        final Map<Term, Predicate> matePredicates = Maps.newHashMap();
        final Map<Term, Predicate> semaforPredicates = Maps.newHashMap();

        // Remove predicates with invalid head
        for (final Predicate predicate : ImmutableList.copyOf(document.getPredicates())) {
            if (NAFUtils.extractHead(document, predicate.getSpan()) == null) {
                document.removeAnnotation(predicate);
                LOGGER.debug("Removed {} without valid head term", predicate);
            }
        }

        // TODO: remove once fixed - normalize Semafor roles
        // if (this.srlEnableSemafor) {
        // for (final Predicate predicate : document.getPredicates()) {
        // if (predicate.getId().startsWith("f_pr")
        // || "semafor".equalsIgnoreCase(predicate.getSource())) {
        // for (final Role role : predicate.getRoles()) {
        // role.setSemRole("");
        // final Term head = NAFUtils.extractHead(document, role.getSpan());
        // if (head != null) {
        // final Span<Term> newSpan = KAFDocument.newTermSpan(Ordering.from(
        // Term.OFFSET_COMPARATOR).sortedCopy(
        // document.getTermsByDepAncestors(ImmutableList.of(head))));
        // role.setSpan(newSpan);
        // }
        // }
        // }
        // }
        // }

        // TODO: remove alignments from PM
        // for (final Predicate predicate : document.getPredicates()) {
        // if (!predicate.getId().startsWith("f_pr")
        // && !"semafor".equalsIgnoreCase(predicate.getSource())) {
        // NAFUtils.removeRefs(predicate, "FrameNet", null);
        // for (final Role role : predicate.getRoles()) {
        // NAFUtils.removeRefs(role, "FrameNet", null);
        // }
        // }
        // }

        // Remove predicates from non-enabled tools (Mate, Semafor)
        for (final Predicate predicate : Lists.newArrayList(document.getPredicates())) {
            final boolean isSemafor = predicate.getId().startsWith("f_pr")
                    || "semafor".equalsIgnoreCase(predicate.getSource());
            if (isSemafor && !this.srlEnableSemafor || !isSemafor && !this.srlEnableMate) {
                document.removeAnnotation(predicate);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Removed " + NAFUtils.toString(predicate) + " (disabled)");
                }
            } else {
                final Term term = NAFUtils.extractHead(document, predicate.getSpan());
                (isSemafor ? semaforPredicates : matePredicates).put(term, predicate);
            }
        }

        // For each Semafor predicate, merge a corresponding Mate predicate for the same term
        for (final Map.Entry<Term, Predicate> entry : semaforPredicates.entrySet()) {
            final Term term = entry.getKey();
            final Predicate semaforPredicate = entry.getValue();
            final Predicate matePredicate = matePredicates.get(term);
            if (matePredicate != null) {

                // Determine whether FrameNet predicate corresponds (-> FN data can be merged)
                final ExternalRef semaforRef = NAFUtils.getRef(semaforPredicate, "FrameNet", null);
                final ExternalRef mateRef = NAFUtils.getRef(matePredicate, "FrameNet", null);
                final boolean mergeFramenet = semaforRef != null && mateRef != null
                        && semaforRef.getReference().equalsIgnoreCase(mateRef.getReference());

                // Merge predicate types
                for (final ExternalRef ref : NAFUtils.getRefs(matePredicate, null, null)) {
                    if (!ref.getResource().equalsIgnoreCase("FrameNet")) {
                        NAFUtils.addRef(semaforPredicate, new ExternalRef(ref));
                    }
                }

                // Merge roles
                for (final Role mateRole : matePredicate.getRoles()) {
                    boolean addRole = true;
                    final Set<Term> mateTerms = ImmutableSet
                            .copyOf(mateRole.getSpan().getTargets());
                    for (final Role semaforRole : semaforPredicate.getRoles()) {
                        final Set<Term> semaforTerms = ImmutableSet
                                .copyOf(semaforRole.getSpan().getTargets());
                        if (mateTerms.equals(semaforTerms)) {
                            addRole = false;
                            semaforRole.setSemRole(mateRole.getSemRole());
                            final boolean addFramenetRef = mergeFramenet
                                    && NAFUtils.getRef(semaforRole, "FrameNet", null) != null;
                            for (final ExternalRef ref : mateRole.getExternalRefs()) {
                                if (!ref.getResource().equalsIgnoreCase("FrameNet")
                                        || addFramenetRef) {
                                    semaforRole.addExternalRef(new ExternalRef(ref));
                                }
                            }
                        }
                    }
                    if (addRole) {
                        final Role semaforRole = document.newRole(semaforPredicate,
                                mateRole.getSemRole(), mateRole.getSpan());
                        semaforPredicate.addRole(semaforRole);
                        for (final ExternalRef ref : mateRole.getExternalRefs()) {
                            semaforRole.addExternalRef(new ExternalRef(ref));
                        }
                    }
                }

                // Delete original Mate predicate
                document.removeAnnotation(matePredicate);

                // Log operation
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Merged " + NAFUtils.toString(matePredicate) + " into "
                            + NAFUtils.toString(semaforPredicate)
                            + (mergeFramenet ? " (including FrameNet data)" : ""));
                }

            }
        }
    }

    private void applySRLRemoveWrongRefs(final KAFDocument document) {

        // Scan all predicates in the SRL layer
        for (final Predicate predicate : Lists.newArrayList(document.getPredicates())) {

            // Extract correct lemma from predicate term
            final Term head = document.getTermsHead(predicate.getTerms());
            final String expectedLemma = head.getLemma();

            // Determine which resource to look for: PropBank vs NomBank
            final String resource = head.getPos().equalsIgnoreCase("V") ? "propbank" : "nombank";

            // Clean rolesets
            final List<ExternalRef> refs = NAFUtils.getRefs(predicate, resource, null);
            Integer expectedSense = null;
            for (final ExternalRef ref : refs) {
                if (ref.getSource() != null) {
                    expectedSense = NAFUtils.extractSense(ref.getReference());
                    break;
                }
            }
            for (final ExternalRef ref : refs) {
                final String lemma = NAFUtils.extractLemma(ref.getReference());
                final Integer sense = NAFUtils.extractSense(ref.getReference());
                if (!expectedLemma.equalsIgnoreCase(lemma)
                        || expectedSense != null && !expectedSense.equals(sense)) {
                    NAFUtils.removeRefs(predicate, resource, ref.getReference());
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Removed wrong roleset '" + ref.getReference() + "' for "
                                + NAFUtils.toString(predicate));
                    }
                }
            }

            // Clean roles
            for (final Role role : predicate.getRoles()) {
                final Integer expectedNum = NAFUtils.extractArgNum(role.getSemRole());
                for (final ExternalRef ref : NAFUtils.getRefs(role, resource, null)) {
                    final String lemma = NAFUtils.extractLemma(ref.getReference());
                    final Integer sense = NAFUtils.extractSense(ref.getReference());
                    final Integer num = NAFUtils.extractArgNum(ref.getReference());
                    if (!Objects.equal(expectedNum, num) || !expectedLemma.equalsIgnoreCase(lemma)
                            || expectedSense != null && !expectedSense.equals(sense)) {
                        role.getExternalRefs().remove(ref);
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Removed wrong role '" + ref.getReference() + "' for "
                                    + NAFUtils.toString(predicate));
                        }
                    }
                }
            }
        }
    }

    private void applySRLRemoveUnknownPredicates(final KAFDocument document) {

        // Scan all predicates in the SRL layer
        for (final Predicate predicate : Lists.newArrayList(document.getPredicates())) {

            // Determine whether the predicate is a verb and thus which resource to check for>
            final Term head = document.getTermsHead(predicate.getTerms());
            final boolean isVerb = head.getPos().equalsIgnoreCase("V");
            final String resource = isVerb ? "propbank" : "nombank";

            // Predicate is invalid if its roleset is unknown in NomBank / PropBank
            for (final ExternalRef ref : NAFUtils.getRefs(predicate, resource, null)) {
                final String roleset = ref.getReference();
                if (isVerb && PropBank.getRoleset(roleset) == null
                        || !isVerb && NomBank.getRoleset(roleset) == null) {
                    document.removeAnnotation(predicate);
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Removed " + NAFUtils.toString(predicate)
                                + " with unknown sense '" + roleset + "' in resource " + resource);
                    }
                    break;
                }
            }
        }
    }

    private void applySRLPredicateAddition(final KAFDocument document) {

        for (final Term term : document.getTerms()) {

            // Ignore terms already marked as predicates or timex or that are part of proper names
            final char pos = Character.toUpperCase(term.getPos().charAt(0));
            if (pos != 'V' && pos != 'N' && pos != 'G' && pos != 'A'
                    || !document.getPredicatesByTerm(term).isEmpty()
                    || !document.getTimeExsByWF(term.getWFs().get(0)).isEmpty()) {
                continue;
            }

            // Identify the smallest entity the term belongs to, if any, in which case require
            // the term to be the head of the entity. This will discard other terms inside an
            // entity (even if nouns), thus enforcing a policy where entities are indivisible
            Entity entity = null;
            for (final Entity e : document.getEntitiesByTerm(term)) {
                if (entity == null || e.getTerms().size() < entity.getTerms().size()) {
                    entity = e;
                    break;
                }
            }
            if (entity != null && term != document.getTermsHead(entity.getTerms())) {
                continue;
            }

            // Decide if a predicate can be added and, in case, which is its roleset,
            // distinguishing between verbs (-> PropBank) and other terms (-> NomBank)
            ExternalRef ref = null;
            final String lemma = term.getLemma();
            if (pos == 'V') {
                final List<PropBank.Roleset> rolesets = PropBank.getRolesets(lemma);
                if (rolesets.size() == 1) {
                    final String rolesetID = rolesets.get(0).getID();
                    ref = document.newExternalRef(NAFUtils.RESOURCE_PROPBANK, rolesetID);
                }
            } else {
                final List<NomBank.Roleset> rolesets = NomBank.getRolesetsForLemma(lemma);
                if (rolesets.size() == 1) {
                    final String rolesetID = rolesets.get(0).getId();
                    ref = document.newExternalRef(NAFUtils.RESOURCE_NOMBANK, rolesetID);
                }
            }

            // Create the predicate, if possible
            if (ref != null) {
                final Predicate predicate = document.newPredicate(
                        KAFDocument.newTermSpan(Collections.singletonList(term), term));
                predicate.addExternalRef(ref);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Added " + NAFUtils.toString(predicate) + ", sense '"
                            + ref.getReference() + "'");
                }
            }
        }
    }

    private void applySRLSelfArgFixing(final KAFDocument document) {

        for (final Predicate predicate : document.getPredicates()) {

            // Skip verbs
            final Term predTerm = predicate.getTerms().get(0);
            if (predTerm.getPos().equalsIgnoreCase("V")) {
                continue;
            }

            // Retrieve the NomBank roleset for current predicate, if known. Skip otherwise
            final String rolesetID = NAFUtils.getRoleset(predicate);
            final NomBank.Roleset roleset = rolesetID == null ? null
                    : NomBank.getRoleset(rolesetID);
            if (roleset == null) {
                continue;
            }

            // Retrieve mandatory and optional roles associated to NomBank roleset
            final List<Integer> mandatoryArgs = roleset.getPredMandatoryArgNums();
            final List<Integer> optionalArgs = roleset.getPredOptionalArgNums();

            // Check current role assignment to predicate term. Mark it as invalid if necessary
            int currentNum = -1;
            for (final Role role : ImmutableList.copyOf(predicate.getRoles())) {
                final Term headTerm = document.getTermsHead(role.getTerms());
                if (headTerm == predTerm && role.getSemRole() != null) {
                    boolean valid = false;
                    final Matcher matcher = SRL_ROLE_PATTERN.matcher(role.getSemRole());
                    if (matcher.matches()) {
                        currentNum = Integer.parseInt(matcher.group(1));
                        valid = roleset.getPredMandatoryArgNums().contains(currentNum)
                                || roleset.getPredOptionalArgNums().contains(currentNum);
                    }
                    if (!valid) {
                        predicate.removeRole(role);
                        LOGGER.debug("Removed " + NAFUtils.toString(role) + " for "
                                + NAFUtils.toString(predicate) + " (mandatory " + mandatoryArgs
                                + ", optional " + optionalArgs + ")");
                    }
                }
            }

            // Add missing role marking, if necessary
            if (!roleset.getPredMandatoryArgNums().isEmpty()) {
                final List<Integer> args = Lists.newArrayList();
                args.addAll(roleset.getPredMandatoryArgNums());
                args.remove((Object) currentNum);
                for (final Integer arg : args) {
                    final List<Term> terms = Ordering.from(Term.OFFSET_COMPARATOR).sortedCopy(
                            document.getTermsByDepAncestors(Collections.singleton(predTerm)));
                    final Span<Term> span = KAFDocument.newTermSpan(terms, predTerm);
                    final String semRole = "A" + arg;
                    final Role role = document.newRole(predicate, semRole, span);
                    predicate.addRole(role);
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Added " + NAFUtils.toString(role) + " to "
                                + NAFUtils.toString(predicate));
                    }
                }
            }
        }
    }

    private void applySRLSenseMapping(final KAFDocument document) {

        for (final Predicate predicate : document.getPredicates()) {

            // Apply specific mappings
            mapExternalRefs(predicate, MAPPING_PREDICATES);

            // Apply Predicate Matrix mappings, if enabled
            NomBank.Roleset nbRoleset = null;
            PropBank.Roleset pbRoleset = null;
            if (this.srlSenseMappingPM) {
                // Obtain the PropBank roleset, either directly or mapping from NomBank
                if (predicate.getTerms().get(0).getPos().equalsIgnoreCase("V")) {
                    final ExternalRef ref = predicate.getExternalRef(NAFUtils.RESOURCE_PROPBANK);
                    pbRoleset = ref == null ? null : PropBank.getRoleset(ref.getReference());
                } else {
                    final ExternalRef ref = predicate.getExternalRef(NAFUtils.RESOURCE_NOMBANK);
                    nbRoleset = ref == null ? null : NomBank.getRoleset(ref.getReference());
                    final String pbSense = nbRoleset == null ? null : nbRoleset.getPBId();
                    pbRoleset = pbSense == null ? null : PropBank.getRoleset(pbSense);
                }

                // Skip the predicate if the PropBank roleset could not be obtained
                if (pbRoleset != null) {
                    // Add an external ref for the PropBank roleset, if missing
                    if (NAFUtils.getRef(predicate, NAFUtils.RESOURCE_PROPBANK,
                            pbRoleset.getID()) == null) {
                        NAFUtils.addRef(predicate, document.newExternalRef( //
                                NAFUtils.RESOURCE_PROPBANK, pbRoleset.getID()));
                    }

                    // Apply mappings from the predicate matrix (indexed in PropBank.Roleset
                    // object)
                    for (final String vnFrame : pbRoleset.getVNFrames()) {
                        NAFUtils.setRef(predicate,
                                document.newExternalRef(NAFUtils.RESOURCE_VERBNET, vnFrame));
                    }
                    for (final String fnFrame : pbRoleset.getFNFrames()) {
                        NAFUtils.setRef(predicate,
                                document.newExternalRef(NAFUtils.RESOURCE_FRAMENET, fnFrame));
                    }
                }
            }

            // Map predicate roles
            for (final Role role : predicate.getRoles()) {

                // Add missing ref if necessary
                if (role.getSemRole().startsWith("A")) {
                    final boolean verb = NAFUtils.extractHead(document, predicate.getSpan())
                            .getMorphofeat().startsWith("VB");
                    final String resource = verb ? "PropBank" : "NomBank";
                    final ExternalRef ref = NAFUtils.getRef(predicate, resource, null);
                    if (ref != null) {
                        final String r = role.getSemRole().startsWith("AM-")
                                ? role.getSemRole().substring(3)
                                : role.getSemRole().substring(1);
                        role.addExternalRef(new ExternalRef(resource,
                                ref.getReference() + "@" + r.toLowerCase()));
                    }
                }

                // Apply specific mappings
                mapExternalRefs(role, MAPPING_ARGUMENTS);

                // Apply Predicate Matrix mappings, if enabled
                if (this.srlSenseMappingPM) {
                    final String semRole = role.getSemRole();
                    final char numChar = semRole.charAt(semRole.length() - 1);
                    if (semRole != null && Character.isDigit(numChar)) {

                        // Determine the PropBank arg num
                        final int num = Character.digit(numChar, 10);
                        final int pbNum = nbRoleset == null ? num : nbRoleset.getArgPBNum(num);
                        if (pbNum < 0) {
                            continue;
                        }
                        final String pbRole = pbRoleset.getID() + '@' + pbNum;
                        // final String pbRole = semRole.substring(0, semRole.length() - 2) +
                        // pbNum;

                        // Create an external ref for the PropBank role, if missing
                        if (NAFUtils.getRef(role, NAFUtils.RESOURCE_PROPBANK, pbRole) == null) {
                            NAFUtils.setRef(role,
                                    document.newExternalRef(NAFUtils.RESOURCE_PROPBANK, pbRole));
                        }

                        // Apply mappings from the predicate matrix
                        for (final String vnRole : pbRoleset.getArgVNRoles(pbNum)) {
                            NAFUtils.setRef(role,
                                    document.newExternalRef(NAFUtils.RESOURCE_VERBNET, vnRole));
                        }
                        for (final String fnRole : pbRoleset.getArgFNRoles(pbNum)) {
                            NAFUtils.setRef(role,
                                    document.newExternalRef(NAFUtils.RESOURCE_FRAMENET, fnRole));
                        }
                    }
                }
            }
        }
    }

    private void applySRLFrameBaseMapping(final KAFDocument document) {

        // Process each predicate and role in the SRL layer
        for (final Predicate predicate : document.getPredicates()) {

            // Determine the POS necessary for FrameBase disambiguation (n/a/v/other)
            final Term head = NAFUtils.extractHead(document, predicate.getSpan());
            final FrameBase.POS pos = FrameBase.POS.forPennTag(head.getMorphofeat());

            // Determine the lemma, handling multiwords
            final StringBuilder builder = new StringBuilder();
            for (final Term term : predicate.getSpan().getTargets()) {
                builder.append(builder.length() == 0 ? "" : "_");
                builder.append(term.getLemma().toLowerCase());
            }
            final String lemma = builder.toString();

            // Convert FrameNet refs to FrameBase refs at the predicate level
            for (final ExternalRef ref : ImmutableList.copyOf(predicate.getExternalRefs())) {
                if (ref.getResource().equalsIgnoreCase("framenet")) {
                    final String frame = ref.getReference();
                    final IRI fnClass = FrameBase.classFor(frame, lemma, pos);
                    if (fnClass != null) {
                        NAFUtils.setRef(predicate,
                                new ExternalRef("FrameBase", fnClass.getLocalName()));
                    }
                }
            }

            // Convert FrameNet refs to FrameBase refs at the role level
            for (final Role role : predicate.getRoles()) {
                for (final ExternalRef ref : ImmutableList.copyOf(role.getExternalRefs())) {
                    if (ref.getResource().equalsIgnoreCase("framenet")) {
                        final String s = ref.getReference();
                        final int index = s.indexOf('@');
                        if (index > 0) {
                            final String frame = s.substring(0, index);
                            final String fe = s.substring(index + 1);
                            final IRI fnProperty = FrameBase.propertyFor(frame, fe);
                            if (fnProperty != null) {
                                NAFUtils.setRef(role,
                                        new ExternalRef("FrameBase", fnProperty.getLocalName()));
                            }
                        }
                    }
                }
            }
        }
    }

    private void applySRLRoleLinking(final KAFDocument document) {

        // Process all the roles in the SRL layer
        for (final Predicate predicate : Lists.newArrayList(document.getPredicates())) {
            for (final Role role : predicate.getRoles()) {

                // Identify the role head. Skip if not found.
                final Term head = NAFUtils.extractHead(document, role.getSpan());
                if (head == null) {
                    continue;
                }

                // Identify the terms that can be linked
                final Set<Term> argTerms = document
                        .getTermsByDepAncestors(Collections.singleton(head), PARTICIPATION_REGEX);

                // Perform the linking, possible augmenting terms using coref info
                linkEntitiesTimexPredicates(document, role, role.getSpan(), argTerms,
                        this.srlRoleLinkingUsingCoref);
            }
        }
    }

    private void applyOpinionLinking(final KAFDocument document) {

        // Process all the opinions in the NAF document
        for (final Opinion opinion : document.getOpinions()) {

            // Add links for the opinion expression, if any
            final OpinionExpression expression = opinion.getOpinionExpression();
            if (expression != null) {
                linkEntitiesTimexPredicates(document, expression, expression.getSpan(),
                        NAFUtils.extractHeads(document, null, expression.getTerms(),
                                NAFUtils.matchExtendedPos(document, "NN", "VB", "JJ", "R")),
                        this.opinionLinkingUsingCoref);
            }

            // Add links for the opinion holder, if any
            final OpinionHolder holder = opinion.getOpinionHolder();
            if (holder != null) {
                linkEntitiesTimexPredicates(document, holder, holder.getSpan(),
                        NAFUtils.extractHeads(document, null, holder.getTerms(), NAFUtils
                                .matchExtendedPos(document, "NN", "PRP", "JJP", "DTP", "WP")),
                        this.opinionLinkingUsingCoref);
            }

            // Add links for the opinion target, if any
            final OpinionTarget target = opinion.getOpinionTarget();
            if (target != null) {
                linkEntitiesTimexPredicates(
                        document, target, target.getSpan(), NAFUtils
                                .extractHeads(document, null, target.getTerms(),
                                        NAFUtils.matchExtendedPos(document, "NN", "PRP", "JJP",
                                                "DTP", "WP", "VB")),
                        this.opinionLinkingUsingCoref);
            }
        }
    }

    private static void linkEntitiesTimexPredicates(final KAFDocument document,
            final Object annotation, final Span<Term> spanToModify, final Set<Term> heads,
            final boolean useCoref) {

        // Add heads to span, if possible
        spanToModify.getHeads().clear();
        if (!heads.isEmpty()) {
            spanToModify.getHeads().addAll(heads);
        }

        // Apply coreference if specified
        Set<Term> linkableTerms = heads;
        if (useCoref) {
            linkableTerms = Sets.newHashSet(heads);
            for (final Term argTerm : heads) {
                for (final Coref coref : document.getCorefsByTerm(argTerm)) {
                    final List<Term> spanHeads = Lists.newArrayList();
                    for (final Span<Term> span : coref.getSpans()) {
                        final Term spanHead = NAFUtils.extractHead(document, span);
                        if (spanHead != null) {
                            spanHeads.add(spanHead);
                        }
                    }
                    if (spanHeads.contains(argTerm)) {
                        for (final Term spanHead : spanHeads) {
                            linkableTerms.addAll(document.getTermsByDepAncestors(
                                    Collections.singleton(spanHead), "(COORD CONJ?)*"));
                        }
                    }
                }
            }
        }

        // Add external refs for the entities, timex and predicates corresponding to sel. terms
        for (final Term term : linkableTerms) {

            // Determine whether the term was obtained via coreference
            final boolean isCoref = !heads.contains(term);

            // Add links for entities
            for (final Entity entity : document.getEntitiesByTerm(term)) {
                for (final Span<Term> span : entity.getSpans()) {
                    final Term spanHead = NAFUtils.extractHead(document, span);
                    if (term.equals(spanHead)) {
                        final String res = isCoref ? NAFUtils.RESOURCE_ENTITY_COREF
                                : NAFUtils.RESOURCE_ENTITY_REF;
                        NAFUtils.setRef(annotation, document.newExternalRef(res, entity.getId()));
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Linked {} to {} as {}", NAFUtils.toString(entity),
                                    NAFUtils.toString(annotation), res);
                        }
                    }
                }
            }

            // Add links for timex
            for (final Timex3 timex : document.getTimeExsByWF(term.getWFs().get(0))) {
                final Term timexHead = NAFUtils.extractHead(document, KAFDocument
                        .newTermSpan(document.getTermsByWFs(timex.getSpan().getTargets())));
                if (term.equals(timexHead)) {
                    final String res = isCoref ? NAFUtils.RESOURCE_TIMEX_COREF
                            : NAFUtils.RESOURCE_TIMEX_REF;
                    NAFUtils.setRef(annotation, document.newExternalRef(res, timex.getId()));
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Linked {} to {} as {}", NAFUtils.toString(timex),
                                NAFUtils.toString(annotation), res);
                    }
                }
            }

            // Add links for predicates
            for (final Predicate pred : document.getPredicatesByTerm(term)) {
                if (term.equals(NAFUtils.extractHead(document, pred.getSpan()))) {
                    final String res = isCoref ? NAFUtils.RESOURCE_PREDICATE_COREF
                            : NAFUtils.RESOURCE_PREDICATE_REF;
                    NAFUtils.setRef(annotation, document.newExternalRef(res, pred.getId()));
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Linked {} to {} as {}", NAFUtils.toString(pred),
                                NAFUtils.toString(annotation), res);
                    }
                }
            }
        }
    }

    private void mapExternalRefs(final Object annotation,
            final Multimap<String, String> mappings) {

        // Keep track of prefixes (NB, PB, VN, FN) of resources already available, as well as the
        // keys corresponding to their values
        final Set<String> prefixes = Sets.newHashSet();
        final Set<String> keys = Sets.newHashSet();

        // Extract prefixes and keys
        for (final ExternalRef ref : NAFUtils.getRefs(annotation, null, null)) {
            final String prefix = MAPPING_PREFIXES.get(ref.getResource().toLowerCase());
            if (prefix != null) {
                prefixes.add(prefix);
                keys.add(prefix + ":" + ref.getReference());
            }
        }

        // Apply mappings
        final List<String> queue = Lists.newLinkedList(keys);
        while (!queue.isEmpty()) {
            final String key = queue.remove(0);
            for (final String mappedKey : mappings.get(key)) {
                final String mappedPrefix = mappedKey.substring(0, 2);
                if (!prefixes.contains(mappedPrefix) && !keys.contains(mappedKey)) {
                    final String mappedResource = MAPPING_PREFIXES.inverse().get(mappedPrefix);
                    final String mappedReference = mappedKey.substring(3);
                    keys.add(mappedKey);
                    queue.add(mappedKey);
                    NAFUtils.addRef(annotation, new ExternalRef(mappedResource, mappedReference));
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Mapped {} : {} to {} for {}", mappedResource,
                                mappedReference, mappedKey, NAFUtils.toString(annotation));
                    }
                }
            }
        }
    }

    private void applySRLPreMOnIRIs(final KAFDocument document) {
        // Process each predicate and role in the SRL layer

        final List<String> models = Arrays.asList(NAFUtils.RESOURCE_FRAMENET,
                NAFUtils.RESOURCE_VERBNET, NAFUtils.RESOURCE_PROPBANK, NAFUtils.RESOURCE_NOMBANK);

        for (final Predicate predicate : document.getPredicates()) {

            List<ExternalRef> allPredicateExtRefs = predicate.getExternalRefs();
            List<ExternalRef> predicateExtRefToRemove = Lists.newArrayList();

            for (final ExternalRef predRef : ImmutableList.copyOf(allPredicateExtRefs)) {
                String refStr = predRef.getResource();

                if (models.contains(refStr)) {
                    final String pred = predRef.getReference();
                    final String source = predRef.getSource();

                    final IRI premonIRI = NAFUtils.createPreMOnSemanticClassIRIfor(refStr, pred);
                    if (premonIRI != null) {
                        ExternalRef e = new ExternalRef("PreMOn+" + refStr,
                                premonIRI.getLocalName());
                        if (source != null)
                            e.setSource(source);
                        NAFUtils.setRef(predicate, e);

                    }

                    predicateExtRefToRemove.add(predRef);
                }

            }

            // remove old predicate ref
            for (ExternalRef toBeDropped : predicateExtRefToRemove) {
                allPredicateExtRefs.remove(toBeDropped);
            }

            // Convert FrameNet refs to FrameBase refs at the role level
            for (final Role role : predicate.getRoles()) {

                List<ExternalRef> allRoleExtRefs = role.getExternalRefs();
                List<ExternalRef> roleExtRefToRemove = Lists.newArrayList();

                for (final ExternalRef roleRef : ImmutableList.copyOf(allRoleExtRefs)) {

                    String refStr = roleRef.getResource();

                    if (models.contains(refStr)) {

                        final String predicateAndRole = roleRef.getReference();
                        final String source = roleRef.getSource();
                        final int index = predicateAndRole.indexOf('@');
                        if (index > 0) {
                            final String pred = predicateAndRole.substring(0, index);
                            final String rol = predicateAndRole.substring(index + 1);

                            final IRI premonIRI = NAFUtils.createPreMOnSemanticRoleIRIfor(refStr,
                                    pred, rol);
                            if (premonIRI != null) {
                                ExternalRef e = new ExternalRef("PreMOn+" + refStr,
                                        premonIRI.getLocalName());
                                if (source != null)
                                    e.setSource(source);
                                NAFUtils.setRef(role, e);
                            }
                        }
                        roleExtRefToRemove.add(roleRef);
                    }
                }
                // remove old role
                for (ExternalRef toBeRemoved : roleExtRefToRemove) {
                    allRoleExtRefs.remove(toBeRemoved);
                }
            }
        }
    }

    /**
     * Returns a new configurable {@code Builder} for the instantiation of a {@code NAFFilter}.
     *
     * @return a new {@code Builder}
     */
    public static final Builder builder() {
        return new Builder();
    }

    /**
     * Returns a new configurable {@code Builder} with all {@code NAFFilter} features either
     * enabled or disabled, based on the supplied parameter.
     *
     * @param enableAll
     *            true, to enable all features; false, to disable all features; null, to maintain
     *            default settings.
     * @return a new {@code Builder}
     */
    public static final Builder builder(@Nullable final Boolean enableAll) {
        return new Builder() //
                .withTermSenseCompletion(enableAll) //
                .withEntityRemoveOverlaps(enableAll) //
                .withEntitySpanFixing(enableAll) //
                .withEntityAddition(enableAll) //
                .withCorefSpanFixing(enableAll) //
                .withCorefForRoleDependencies(enableAll) //
                .withLinkingCompletion(enableAll) //
                .withLinkingFixing(enableAll) //
                .withSRLRemoveWrongRefs(enableAll) //
                .withSRLRemoveUnknownPredicates(enableAll) //
                .withSRLPredicateAddition(enableAll) //
                .withSRLSelfArgFixing(enableAll) //
                .withSRLSenseMapping(enableAll) //
                .withSRLRoleLinking(enableAll, enableAll) //
                .withOpinionLinking(enableAll, enableAll).withSRLPreMOnIRIs(enableAll);
    }

    /**
     * Configurable builder object for the creation of {@code NAFFilter}s.
     * <p>
     * Supported properties accepted by {@link #withProperties(Map, String)} and corresponding
     * setter methods:
     * </p>
     * <table border="1">
     * <thead>
     * <tr>
     * <th>Property</th>
     * <th>Values</th>
     * <th>Corresponding method</th>
     * <th>Default</th>
     * </tr>
     * </thead><tbody>
     * <tr>
     * <td>termSenseFiltering</td>
     * <td>true, false</td>
     * <td>{@link #withTermSenseFiltering(Boolean)}</td>
     * <td>true</td>
     * </tr>
     * <tr>
     * <td>termSenseCompletion</td>
     * <td>true, false</td>
     * <td>{@link #withTermSenseCompletion(Boolean)}</td>
     * <td>true</td>
     * </tr>
     * <tr>
     * <td>entityRemoveOverlaps</td>
     * <td>true, false</td>
     * <td>{@link #withEntityRemoveOverlaps(Boolean)}</td>
     * <td>true</td>
     * </tr>
     * <tr>
     * <td>entitySpanFixing</td>
     * <td>true, false</td>
     * <td>{@link #withEntitySpanFixing(Boolean)}</td>
     * <td>true</td>
     * </tr>
     * <tr>
     * <td>entityAddition</td>
     * <td>true, false</td>
     * <td>{@link #withEntityAddition(Boolean)}</td>
     * <td>true</td>
     * </tr>
     * <tr>
     * <td>entityValueNormalization</td>
     * <td>true, false</td>
     * <td>{@link #withEntityValueNormalization(Boolean)}</td>
     * <td>true</td>
     * </tr>
     * <tr>
     * <td>linkingCompletion</td>
     * <td>true, false</td>
     * <td>{@link #withLinkingCompletion(Boolean)}</td>
     * <td>true</td>
     * </tr>
     * <tr>
     * <td>linkingFixing</td>
     * <td>true, false</td>
     * <td>{@link #withLinkingFixing(Boolean)}</td>
     * <td>false</td>
     * </tr>
     * <tr>
     * <td>corefForRoleDependencies</td>
     * <td>true, false</td>
     * <td>{@link #withCorefForRoleDependencies(Boolean)}</td>
     * <td>false</td>
     * </tr>
     * <tr>
     * <td>corefSpanFixing</td>
     * <td>true, false</td>
     * <td>{@link #withCorefSpanFixing(Boolean)}</td>
     * <td>false</td>
     * </tr>
     * <tr>
     * <td>srlRemoveWrongRefs</td>
     * <td>true, false</td>
     * <td>{@link #withSRLRemoveWrongRefs(Boolean)}</td>
     * <td>true</td>
     * </tr>
     * <tr>
     * <td>srlRemoveUnknownPredicates</td>
     * <td>true, false</td>
     * <td>{@link #withSRLRemoveUnknownPredicates(Boolean)}</td>
     * <td>false</td>
     * </tr>
     * <tr>
     * <td>srlPredicateAddition</td>
     * <td>true, false</td>
     * <td>{@link #withSRLPredicateAddition(Boolean)}</td>
     * <td>true</td>
     * </tr>
     * <tr>
     * <td>srlSelfArgFixing</td>
     * <td>true, false</td>
     * <td>{@link #withSRLSelfArgFixing(Boolean)}</td>
     * <td>true</td>
     * </tr>
     * <tr>
     * <td>srlSenseMapping</td>
     * <td>true, false</td>
     * <td>{@link #withSRLSenseMapping(Boolean)}</td>
     * <td>false</td>
     * </tr>
     * <tr>
     * <td>srlFrameBaseMapping</td>
     * <td>true, false</td>
     * <td>{@link #withSRLFrameBaseMapping(Boolean)}</td>
     * <td>true</td>
     * </tr>
     * <tr>
     * <td>srlRoleLinking</td>
     * <td>none, exact, coref</td>
     * <td>{@link #withSRLRoleLinking(Boolean, Boolean)}</td>
     * <td>coref (= true, true)</td>
     * </tr>
     * <tr>
     * <td>opinionLinking</td>
     * <td>none, exact, coref</td>
     * <td>{@link #withOpinionLinking(Boolean, Boolean)}</td>
     * <td>coref (= true, true)</td>
     * </tr>
     * </tbody>
     * </table>
     */
    public static final class Builder {

        @Nullable
        private Boolean termSenseFiltering;

        @Nullable
        private Boolean termSenseCompletion;

        @Nullable
        private Boolean entityRemoveOverlaps;

        @Nullable
        private Boolean entitySpanFixing;

        @Nullable
        private Boolean entityAddition;

        @Nullable
        private Boolean entityValueNormalization;

        @Nullable
        private Boolean linkingCompletion;

        @Nullable
        private Boolean linkingFixing;

        @Nullable
        private Boolean corefSpanFixing;

        @Nullable
        private Boolean corefForRoleDependencies;

        @Nullable
        private Boolean srlPreprocess;

        @Nullable
        private Boolean srlEnableMate;

        @Nullable
        private Boolean srlEnableSemafor;

        @Nullable
        private Boolean srlRemoveWrongRefs;

        @Nullable
        private Boolean srlRemoveUnknownPredicates;

        @Nullable
        private Boolean srlPredicateAddition;

        @Nullable
        private Boolean srlSelfArgFixing;

        @Nullable
        private Boolean srlSenseMapping;

        @Nullable
        private Boolean srlFrameBaseMapping;

        @Nullable
        private Boolean srlRoleLinking;

        @Nullable
        private Boolean srlRoleLinkingUsingCoref;

        @Nullable
        private Boolean srlPreMOnIRIs;

        @Nullable
        private Boolean opinionLinking;

        @Nullable
        private Boolean opinionLinkingUsingCoref;

        Builder() {
        }

        /**
         * Sets all the properties in the map supplied, matching an optional prefix.
         *
         * @param properties
         *            the properties to configure, not null
         * @param prefix
         *            an optional prefix used to select the relevant properties in the map
         * @return this builder object, for call chaining
         */
        public Builder withProperties(final Map<?, ?> properties, @Nullable final String prefix) {
            final String p = prefix == null ? "" : prefix.endsWith(".") ? prefix : prefix + ".";
            for (final Map.Entry<?, ?> entry : properties.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null
                        && entry.getKey().toString().startsWith(p)) {
                    final String name = entry.getKey().toString().substring(p.length());
                    final String value = Strings.emptyToNull(entry.getValue().toString());
                    if ("termSenseFiltering".equals(name)) {
                        withTermSenseFiltering(Boolean.valueOf(value));
                    } else if ("termSenseCompletion".equals(name)) {
                        withTermSenseCompletion(Boolean.valueOf(value));
                    } else if ("entityRemoveOverlaps".equals(name)) {
                        withEntityRemoveOverlaps(Boolean.valueOf(value));
                    } else if ("entitySpanFixing".equals(name)) {
                        withEntitySpanFixing(Boolean.valueOf(value));
                    } else if ("entityAddition".equals(name)) {
                        withEntityAddition(Boolean.valueOf(value));
                    } else if ("entityValueNormalization".equals(name)) {
                        withEntityValueNormalization(Boolean.valueOf(value));
                    } else if ("linkingCompletion".equals(name)) {
                        withLinkingCompletion(Boolean.valueOf(value));
                    } else if ("linkingFixing".equals(name)) {
                        withLinkingFixing(Boolean.valueOf(value));
                    } else if ("corefForRoleDependencies".equals(name)) {
                        withCorefForRoleDependencies(Boolean.valueOf(value));
                    } else if ("corefSpanFixing".equals(name)) {
                        withCorefSpanFixing(Boolean.valueOf(value));
                    } else if ("srlPreprocess".equals(name)) {
                        if ("none".equalsIgnoreCase(value)) {
                            withSRLPreprocess(false, false, false);
                        } else if ("basic".equalsIgnoreCase(value)) {
                            withSRLPreprocess(true, false, false);
                        } else if ("mate".equalsIgnoreCase(value)) {
                            withSRLPreprocess(true, true, false);
                        } else if ("semafor".equalsIgnoreCase(value)) {
                            withSRLPreprocess(true, false, true);
                        } else if ("mate+semafor".equalsIgnoreCase(value)) {
                            withSRLPreprocess(true, true, true);
                        } else {
                            throw new IllegalArgumentException("Invalid '" + value
                                    + "' srlPreprocess property. Supported: none basic mate semafor mate+semafor");
                        }
                    } else if ("srlRemoveWrongRefs".equals(name)) {
                        withSRLRemoveWrongRefs(Boolean.valueOf(value));
                    } else if ("srlRemoveUnknownPredicates".equals(name)) {
                        withSRLRemoveUnknownPredicates(Boolean.valueOf(value));
                    } else if ("srlPredicateAddition".equals(name)) {
                        withSRLPredicateAddition(Boolean.valueOf(value));
                    } else if ("srlSelfArgFixing".equals(name)) {
                        withSRLSelfArgFixing(Boolean.valueOf(value));
                    } else if ("srlSenseMapping".equals(name)) {
                        withSRLSenseMapping(Boolean.valueOf(value));
                    } else if ("srlFrameBaseMapping".equals(name)) {
                        withSRLFrameBaseMapping(Boolean.valueOf(value));
                    } else if ("srlRoleLinking".equals(name)) {
                        if ("none".equalsIgnoreCase(value)) {
                            withSRLRoleLinking(false, false);
                        } else if ("exact".equalsIgnoreCase(value)) {
                            withSRLRoleLinking(true, false);
                        } else if ("coref".equalsIgnoreCase(value)) {
                            withSRLRoleLinking(true, true);
                        } else {
                            throw new IllegalArgumentException("Invalid '" + value
                                    + "' srlRoleLinking property. Supported: none exact coref ");
                        }
                    } else if ("srlPreMOnIRIs".equals(name)) {
                        withSRLPreMOnIRIs(Boolean.valueOf(value));
                    } else if ("opinionLinking".equals(name)) {
                        if ("none".equalsIgnoreCase(value)) {
                            withOpinionLinking(false, false);
                        } else if ("exact".equalsIgnoreCase(value)) {
                            withOpinionLinking(true, false);
                        } else if ("coref".equalsIgnoreCase(value)) {
                            withOpinionLinking(true, true);
                        } else {
                            throw new IllegalArgumentException("Invalid '" + value
                                    + "' opinionLinking property. Supported: none exact coref ");
                        }
                    }
                }
            }
            return this;
        }

        /**
         * Specifies whether term senses (BBN, SST, WN Synset, SUMO mapping, YAGO) for proper
         * names should be removed.
         *
         * @param termSenseFiltering
         *            true to enable term sense filtering, null to use default value
         * @return this builder object, for call chaining
         */
        public Builder withTermSenseFiltering(@Nullable final Boolean termSenseFiltering) {
            this.termSenseFiltering = termSenseFiltering;
            return this;
        }

        /**
         * Specifies whether missing term senses (BBN, SST, WN Synset, SUMO mapping ) should be
         * completed by applying sense mappings.
         *
         * @param termSenseCompletion
         *            true to enable term sense completion, null to use default value
         * @return this builder object, for call chaining
         */
        public Builder withTermSenseCompletion(@Nullable final Boolean termSenseCompletion) {
            this.termSenseCompletion = termSenseCompletion;
            return this;
        }

        /**
         * Specifies whether entities overlapping with timex or (larger) entities should be
         * removed.
         *
         * @param entityRemoveOverlaps
         *            true, to enable removal of entities that overlap with other entities or
         *            timex; null to use the default setting
         * @return this builder object for call chaining
         */
        public Builder withEntityRemoveOverlaps(@Nullable final Boolean entityRemoveOverlaps) {
            this.entityRemoveOverlaps = entityRemoveOverlaps;
            return this;
        }

        /**
         * Specifies whether the spans of entities should be checked and possibly fixed, removing
         * determiners and non-alphanumeric terms. If enabled and no terms remain after fixing the
         * span of an entity, that entity is removed.
         *
         * @param entitySpanFixing
         *            true, to enable fixing of entity spans (and possible removal of invalid
         *            entities); null to use the default setting
         * @return this builder object, for call chaining
         */
        public Builder withEntitySpanFixing(@Nullable final Boolean entitySpanFixing) {
            this.entitySpanFixing = entitySpanFixing;
            return this;
        }

        /**
         * Specifies whether new entities should be added to the document for noun phrases not
         * already marked as entities.
         *
         * @param entityAddition
         *            true, to enable entity addition; null, to use the default setting
         * @return this builder object, for call chaining
         */
        public Builder withEntityAddition(@Nullable final Boolean entityAddition) {
            this.entityAddition = entityAddition;
            return this;
        }

        /**
         * Specifies whether normalization of numerical entity values (ordinal, cardinal, percent,
         * money) should take place.
         *
         * @param entityValueNormalization
         *            true, to enable entity value normalization; null, to use the default setting
         * @return this builder object, for call chaining
         */
        public Builder withEntityValueNormalization(
                @Nullable final Boolean entityValueNormalization) {
            this.entityValueNormalization = entityValueNormalization;
            return this;
        }

        /**
         * Specifies whether entity links in the LinkedEntities layer should be applied to
         * entities and predicates where missing, thus performing a kind of linking completion.
         *
         * @param linkingCompletion
         *            true, to perform linking completion
         * @return this builder object, for call chaining
         */
        public Builder withLinkingCompletion(@Nullable final Boolean linkingCompletion) {
            this.linkingCompletion = linkingCompletion;
            return this;
        }

        /**
         * Specifies whether removal of inaccurate entity links to DBpedia should occur. If
         * enabled, links for entities whose span is part of a stop word list are removed. The
         * stop word list contains (multi-)words that are known to be ambiguous from an analysis
         * of Wikipedia data.
         *
         * @param linkingFixing
         *            true to enable linking fixing; null, to use the default setting
         * @return this builder object, for call chaining
         */
        public Builder withLinkingFixing(@Nullable final Boolean linkingFixing) {
            this.linkingFixing = linkingFixing;
            return this;
        }

        /**
         * Specifies whether new coreference relations should be added for APPO/NMOD/TITLE edges
         * in the dependency tree between proper nouns and role nouns.
         *
         * @param corefForRoleDependencies
         *            true to enable addition of coreference relations for APPO/NMOD/TITLE edges;
         *            null, to use the default setting
         * @return this builder object, for call chaining
         */
        public Builder withCorefForRoleDependencies(
                @Nullable final Boolean corefForRoleDependencies) {
            this.corefForRoleDependencies = corefForRoleDependencies;
            return this;
        }

        /**
         * Specifies whether spans of existing coreference sets should be checked and possibly
         * shrinked or removed. The following rules are applied:
         * <ul>
         * <li>remove spans without a well-defined head in the dependency tree;</li>
         * <li>remove spans that enclose another span in the coreference set;</li>
         * <li>remove spans with non NNP head corresponding to a verb or to a NomBank predicate
         * that never admit itself as a role (e.g., 'war' but not 'president'), if no span with a
         * sumo:Process head (= event) is part of the coreference set;</li>
         * <li>shrink spans with non NNP head that contain some NNP token, if a span with NNP head
         * is part of the coreference set;</li>
         * </ul>
         * If a coreference set becomes empty as a result of the above filtering, it is removed
         * from the NAF document.
         *
         * @param corefSpanFixing
         *            true to enable coreference span fixing; null to use default setting
         * @return this builder object, for call chaining
         */
        public Builder withCorefSpanFixing(@Nullable final Boolean corefSpanFixing) {
            this.corefSpanFixing = corefSpanFixing;
            return this;
        }

        /**
         * Specifies whether to preprocess SRL layer, enabling Mate and/or Semafor outputs. If
         * both tools are enabled, they are combined in such a way that semafor takes precedence
         * in case two predicates refer to the same token.
         *
         * @param srlPreprocess
         *            true, to enable preprocessing of SRL layer
         * @param srlEnableMate
         *            true, to enable Mate output
         * @param srlEnableSemafor
         *            true, to enable Semafor output
         * @return this builder object, for call chaining
         */
        public Builder withSRLPreprocess(@Nullable final Boolean srlPreprocess,
                @Nullable final Boolean srlEnableMate, @Nullable final Boolean srlEnableSemafor) {
            this.srlPreprocess = srlPreprocess;
            this.srlEnableMate = srlEnableMate;
            this.srlEnableSemafor = srlEnableSemafor;
            return this;
        }

        /**
         * Specifies whether ExternalRefs with wrong PropBank/NomBank rolesets/roles in the NAF
         * should be removed. A roleset/role is considered wrong if its lemma differs from the one
         * of the predicate in the text (errors can arise from 'excessive' mappings, e.g. in the
         * predicate matrix).
         *
         * @param srlRemoveWrongRefs
         *            true, if removal of ExternalRefs with wrong PB/NB rolesets/roles has to be
         *            enabled
         * @return this builder object, for call chaining
         */
        public Builder withSRLRemoveWrongRefs(@Nullable final Boolean srlRemoveWrongRefs) {
            this.srlRemoveWrongRefs = srlRemoveWrongRefs;
            return this;
        }

        /**
         * Specifies whether SRL predicates with unknown PropBank/NomBank rolesets/roles in the
         * NAF should be removed. A roleset/role is wrong if it does not appear in
         * PropBank/NomBank frame files (SRL tools such as Mate may detect predicates for unknown
         * rolesets, to increase recall).
         *
         * @param srlRemoveUnknownPredicates
         *            true, if removal of predicates with unknown PB/NB rolesets/roles has to be
         *            enabled
         * @return this builder object, for call chaining
         */
        public Builder withSRLRemoveUnknownPredicates(
                @Nullable final Boolean srlRemoveUnknownPredicates) {
            this.srlRemoveUnknownPredicates = srlRemoveUnknownPredicates;
            return this;
        }

        /**
         * Specifies whether new predicates can be added for verbs, noun and adjectives having
         * exactly one sense in PropBank or NomBank but not marked in the text.
         *
         * @param srlPredicateAddition
         *            true, to enable predicate addition; null to use the default setting
         * @return this builder object, for call chaining
         */
        public Builder withSRLPredicateAddition(@Nullable final Boolean srlPredicateAddition) {
            this.srlPredicateAddition = srlPredicateAddition;
            return this;
        }

        /**
         * Specifies whether 'self-roles' can be added for predicates where missing or removed
         * where wrongly added. If set, for each recognized predicate the filter checks whether
         * the predicate term has also been marked as role. IF it is not marked in the NAF but it
         * is always marked in NomBank training set THEN the filter adds a new role for the
         * predicate term, using the semantic role in NomBank training set. If already marked
         * whereas no marking should happen based on previous criteria, then the role is removed.
         *
         * @param srlSelfArgFixing
         *            true if role addition is enabled
         * @return this builder object, for call chaining
         */
        public Builder withSRLSelfArgFixing(@Nullable final Boolean srlSelfArgFixing) {
            this.srlSelfArgFixing = srlSelfArgFixing;
            return this;
        }

        /**
         * Specifies whether mapping of roleset / roles in the SRL layer should take place. If
         * enabled, new external refs are added to map NomBank rolesets and roles to PropBank and
         * to map PropBank rolesets and roles to VerbNet and FrameNet, based on the predicate
         * matrix.
         *
         * @param srlSenseMapping
         *            true, to enable SRL sense mapping; null, to use the default setting
         * @return this builder object, for call chaining
         */
        public Builder withSRLSenseMapping(@Nullable final Boolean srlSenseMapping) {
            this.srlSenseMapping = srlSenseMapping;
            return this;
        }

        /**
         * Specifies whether mapping of rolesets / roles in the SRL layer to FrameBase classes /
         * properties should take place. If enabled, new external refs for FrameBase targets are
         * added where possible.
         *
         * @param srlFrameBaseMapping
         *            true, to enable SRL to FrameBase mapping; null, to use the default setting
         * @return this builder object, for call chaining
         */
        public Builder withSRLFrameBaseMapping(@Nullable final Boolean srlFrameBaseMapping) {
            this.srlFrameBaseMapping = srlFrameBaseMapping;
            return this;
        }

        /**
         * Specifies whether ExternalRef tags should be added to SRL roles to link them to the
         * entities, timex and predicates in the NAF the role corresponds to. The correspondence
         * between a role and entities/predicates is computed based on the evaluation of regular
         * expressions on the dependency tree that take properly into account coordination and
         * prepositions (e.g., in 'Tom speaks to Al, John and the friend of Jack', the A1 role 'to
         * Al, John and the friend of Jack' is linked to the entities 'Al' and 'John' but not
         * 'Jack'). If {@code useCoref} is specified, SRL roles are also linked to entities, timex
         * and predicates reachable via coreference chains.
         *
         * @param srlRoleLinking
         *            true, to enable this filtering; null, to use the default setting
         * @param useCoref
         *            true, to enable linking to coreferring entities/timex/predicates; null, to
         *            use the default setting
         * @return this builder object, for call chaining
         */
        public Builder withSRLRoleLinking(@Nullable final Boolean srlRoleLinking,
                @Nullable final Boolean useCoref) {
            this.srlRoleLinking = srlRoleLinking;
            this.srlRoleLinkingUsingCoref = useCoref;
            return this;
        }

        /**
         * Specifies replace reference of predicate models in NAF with premon IRIs
         *
         * @param srlPreMOnIRIs
         *            true to enable IRI replacement, null to use default value
         * @return this builder object, for call chaining
         */
        public Builder withSRLPreMOnIRIs(@Nullable final Boolean srlPreMOnIRIs) {
            this.srlPreMOnIRIs = srlPreMOnIRIs;
            return this;
        }

        /**
         * Specifies whether ExternalRef tags should be added to opinion expressions, holder and
         * targets to lthe entities, timex and predicates their heads correspond to.
         *
         * @param opinionLinking
         *            true, to enable this linking; null, to use the default setting
         * @param opinionLinkingUsingCoref
         *            true, to enable linking to coreferring entities/timex/predicates; null, to
         *            use the default setting
         * @return this builder object, for call chaining
         */
        public Builder withOpinionLinking(@Nullable final Boolean opinionLinking,
                @Nullable final Boolean opinionLinkingUsingCoref) {
            this.opinionLinking = opinionLinking;
            this.opinionLinkingUsingCoref = opinionLinkingUsingCoref;
            return this;
        }

        /**
         * Creates a {@code NAFFilter} based on the flags specified on this builder object.
         *
         * @return the constructed {@code NAFFilter}
         */
        public NAFFilter build() {
            return new NAFFilter(this);
        }

    }

}
