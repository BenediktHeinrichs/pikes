package eu.fbk.dkm.pikes.rdf;

import java.io.File;
import java.lang.reflect.Array;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import eu.fbk.dkm.pikes.rdf.vocab.*;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.impl.ValueFactoryImpl;
import org.eclipse.rdf4j.model.vocabulary.*;
import org.eclipse.rdf4j.rio.RDFHandler;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import ixa.kaflib.Coref;
import ixa.kaflib.Dep;
import ixa.kaflib.Entity;
import ixa.kaflib.ExternalRef;
import ixa.kaflib.Factuality;
import ixa.kaflib.KAFDocument;
import ixa.kaflib.KAFDocument.FileDesc;
import ixa.kaflib.LinguisticProcessor;
import ixa.kaflib.Opinion;
import ixa.kaflib.Opinion.OpinionHolder;
import ixa.kaflib.Opinion.OpinionTarget;
import ixa.kaflib.Opinion.Polarity;
import ixa.kaflib.Predicate;
import ixa.kaflib.Predicate.Role;
import ixa.kaflib.Span;
import ixa.kaflib.Term;
import ixa.kaflib.Timex3;
import ixa.kaflib.WF;

import eu.fbk.dkm.pikes.naflib.Corpus;
import eu.fbk.dkm.pikes.rdf.util.ModelUtil;
import eu.fbk.dkm.pikes.rdf.util.OWLTime;
import eu.fbk.dkm.pikes.rdf.util.ProcessorASNorm;
import eu.fbk.dkm.pikes.resources.NAFFilter;
import eu.fbk.dkm.pikes.resources.NAFUtils;
import eu.fbk.dkm.pikes.resources.PropBank;
import eu.fbk.dkm.pikes.resources.Sumo;
import eu.fbk.dkm.pikes.resources.WordNet;
import eu.fbk.dkm.pikes.resources.YagoTaxonomy;
import eu.fbk.utils.svm.Util;
import eu.fbk.rdfpro.RDFHandlers;
import eu.fbk.rdfpro.RDFProcessors;
import eu.fbk.rdfpro.RDFSource;
import eu.fbk.rdfpro.RDFSources;
import eu.fbk.rdfpro.util.Environment;
import eu.fbk.rdfpro.util.Hash;
import eu.fbk.rdfpro.util.Options;
import eu.fbk.rdfpro.util.QuadModel;
import eu.fbk.rdfpro.util.Statements;
import eu.fbk.rdfpro.util.Tracker;

// entity.type
// instance

public final class RDFGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(RDFGenerator.class);

    private static final ValueFactory FACTORY = SimpleValueFactory.getInstance();

    // todo adapta to UD
    private static final String MODIFIER_REGEX = "(NMOD|AMOD|TMP|LOC|TITLE) PMOD? (COORD CONJ?)* PMOD?";

    // todo adapta to UD
    private static final String PARTICIPATION_REGEX = ""
            + "SUB? (COORD CONJ?)* (PMOD (COORD CONJ?)*)? ((VC OPRD?)|(IM OPRD?))*";

    private static final Multimap<String, IRI> DEFAULT_TYPE_MAP = ImmutableMultimap
            .<String, IRI>builder() //
            .put("entity.person", NWR.PERSON) //
            .put("entity.per", NWR.PERSON) //
            .put("entity.organization", NWR.ORGANIZATION) //
            .put("entity.org", NWR.ORGANIZATION) //
            .put("entity.location", NWR.LOCATION) //
            .put("entity.loc", NWR.LOCATION) //
            .put("entity.misc", NWR.MISC) //
            .put("entity.money", GR.PRICE_SPECIFICATION) //
            .put("entity.date", OWLTIME.DATE_TIME_INTERVAL) //
            .put("entity.time", OWLTIME.DATE_TIME_INTERVAL) //
            .put("timex.date", OWLTIME.DATE_TIME_INTERVAL) //
            .put("timex.duration", OWLTIME.PROPER_INTERVAL) //
            .build();

    private static final Map<String, String> DEFAULT_NAMESPACE_MAP = ImmutableMap
            .<String, String>builder()
            .put("propbank", "http://www.newsreader-project.eu/ontologies/propbank/")
            .put("nombank", "http://www.newsreader-project.eu/ontologies/nombank/")
            .put("framenet", "http://www.newsreader-project.eu/ontologies/framenet/")
            .put("verbnet", "http://www.newsreader-project.eu/ontologies/verbnet/")
            .put("premon+propbank", "http://premon.fbk.eu/resource/")
            .put("premon+nombank", "http://premon.fbk.eu/resource/")
            .put("premon+framenet", "http://premon.fbk.eu/resource/")
            .put("premon+verbnet", "http://premon.fbk.eu/resource/")
            .put("eso", "http://www.newsreader-project.eu/domain-ontology#")
            .put("framebase", "http://framebase.org/ns/") //
            .put("attribute", "attr:")
            // TODO: change this namespace
            .put("syn", "http://wordnet-rdf.princeton.edu/wn30/")
            // TODO .put("conn", "http://www.newsreader-project.eu/conn/")
            .put("sumo", SUMO.NAMESPACE).put("yago", YagoTaxonomy.NAMESPACE).build();

    private static final String DEFAULT_OWLTIME_NAMESPACE = "http://www.newsreader-project.eu/time/";

    public static final RDFGenerator DEFAULT = RDFGenerator.builder().build();

    private final Multimap<String, IRI> typeMap;

    private final Map<String, String> namespaceMap;

    private final String owltimeNamespace;

    private final boolean merging;

    private final boolean normalization;

    private RDFGenerator(final Builder builder) {
        this.typeMap = ImmutableMultimap
                .copyOf(MoreObjects.firstNonNull(builder.typeMap, DEFAULT_TYPE_MAP));
        this.namespaceMap = ImmutableMap
                .copyOf(MoreObjects.firstNonNull(builder.namespaceMap, DEFAULT_NAMESPACE_MAP));
        this.owltimeNamespace = MoreObjects.firstNonNull(builder.owltimeNamespace,
                DEFAULT_OWLTIME_NAMESPACE);
        this.merging = MoreObjects.firstNonNull(builder.merging, Boolean.FALSE);
        this.normalization = MoreObjects.firstNonNull(builder.normalization, Boolean.FALSE);
    }

    public Model generate(final KAFDocument document,
            @Nullable final Iterable<Integer> sentenceIDs) {
        final Model model = new LinkedHashModel();
        generate(document, sentenceIDs, model);
        return model;
    }

    public void generate(final KAFDocument document, @Nullable final Iterable<Integer> sentenceIDs,
            final Collection<? super Statement> output) {
        final RDFHandler handler = RDFHandlers.wrap(output);
        try {
            generate(document, sentenceIDs, handler);
        } catch (final Throwable ex) {
            throw new RuntimeException("Unexpected exception (!)", ex);
        }
    }

    public void generate(final KAFDocument document, @Nullable final Iterable<Integer> sentenceIDs,
            final RDFHandler handler) throws RDFHandlerException {

        final boolean[] ids = new boolean[document.getNumSentences() + 1];
        if (sentenceIDs == null) {
            Arrays.fill(ids, true);
        } else {
            for (final Integer sentenceID : sentenceIDs) {
                ids[sentenceID] = true;
            }
        }

        final String baseIRI = document.getPublic().uri;
        new Extractor(baseIRI, handler, document, ids).run();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        @Nullable
        private Multimap<String, IRI> typeMap;

        @Nullable
        private Multimap<String, IRI> propertyMap;

        @Nullable
        private Map<String, String> namespaceMap;

        @Nullable
        private String owltimeNamespace;

        @Nullable
        private Boolean merging;

        @Nullable
        private Boolean normalization;

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
                    if ("fusion".equals(name)) {
                        withMerging(Boolean.valueOf(value));
                    } else if ("normalization".equals(name)) {
                        withNormalization(Boolean.valueOf(value));
                    }
                }
            }
            return this;
        }

        public Builder withTypeMap(@Nullable final Multimap<String, IRI> typeMap) {
            this.typeMap = typeMap;
            return this;
        }

        public Builder withPropertyMap(@Nullable final Multimap<String, IRI> propertyMap) {
            this.propertyMap = propertyMap;
            return this;
        }

        public Builder withNamespaceMap(@Nullable final Map<String, String> namespaceMap) {
            this.namespaceMap = namespaceMap;
            return this;
        }

        public Builder withOWLTimeNamespace(@Nullable final String owltimeNamespace) {
            this.owltimeNamespace = owltimeNamespace;
            return this;
        }

        public Builder withMerging(@Nullable final Boolean merging) {
            this.merging = merging;
            return this;
        }

        public Builder withNormalization(@Nullable final Boolean normalization) {
            this.normalization = normalization;
            return this;
        }

        public RDFGenerator build() {
            return new RDFGenerator(this);
        }

    }

    static final class Runner implements Runnable {

        private final Corpus corpus;

        private final RDFGenerator generator;

        private final File outputFile;

        private final boolean intermediate;

        private Runner(final Corpus corpus, final RDFGenerator generator, final File outputFile,
                final boolean split) {
            this.corpus = corpus;
            this.generator = generator;
            this.outputFile = outputFile.getAbsoluteFile();
            this.intermediate = split;
        }

        static Runner create(final String name, final String... args) {
            final Options options = Options
                    .parse("r,recursive|o,output!|m,merge|n,normalize|i,intermediate|+", args);
            final File outputFile = options.getOptionArg("o", File.class);
            final boolean recursive = options.hasOption("r");
            final boolean merge = options.hasOption("m");
            final boolean normalize = options.hasOption("n");
            final boolean intermediate = options.hasOption("i");
            final Corpus corpus = Corpus.create(recursive, options.getPositionalArgs(File.class));
            final RDFGenerator generator = RDFGenerator.builder()
                    .withProperties(Util.PROPERTIES, "eu.fbk.dkm.pikes.rdf.RDFGenerator")
                    .withMerging(merge).withNormalization(normalize).build();
            return new Runner(corpus, generator, outputFile, intermediate);
        }

        @Override
        public void run() {

            LOGGER.info("Converting {} NAF files to RDF", this.corpus.size());

            final NAFFilter filter = NAFFilter.builder()
                    .withProperties(Util.PROPERTIES, "eu.fbk.dkm.pikes.rdf.NAFFilter")
                    .withSRLPreprocess(true, true, true).build();

            final RDFHandler writer;
            if (!this.intermediate) {
                try {
                    Files.createParentDirs(this.outputFile);
                    writer = RDFHandlers.write(null, 1, Runner.this.outputFile.getAbsolutePath());
                    writer.startRDF();
                } catch (final Throwable ex) {
                    throw new RuntimeException(ex);
                }
            } else {
                writer = null;
            }

            final Tracker tracker = new Tracker(LOGGER, null, //
                    "Processed %d NAF files (%d NAF/s avg)", //
                    "Processed %d NAF files (%d NAF/s, %d NAF/s avg)");

            final int numThreads = Environment.getCores();
            final CountDownLatch latch = new CountDownLatch(numThreads);
            final AtomicInteger counter = new AtomicInteger(0);
            final AtomicInteger succeeded = new AtomicInteger(0);
            tracker.start();
            for (int i = 0; i < numThreads; ++i) {
                Environment.getPool().submit(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            final Path outBase = Runner.this.outputFile.toPath().getParent()
                                    .toAbsolutePath().normalize();
                            while (true) {
                                final int i = counter.getAndIncrement();
                                if (i >= Runner.this.corpus.size()) {
                                    break;
                                }
                                String docName = null;

                                final Path path = Runner.this.corpus.file(i);

                                Path output = null;
                                if (Runner.this.intermediate) {
                                    try {
                                        final Path base = Runner.this.corpus.path();
                                        final Path relative = base.toAbsolutePath()
                                                .relativize(path.toAbsolutePath());
                                        String name = relative.toString();
                                        int index = name.indexOf(".naf");
                                        if (index < 0) {
                                            index = name.indexOf(".xml");
                                        }
                                        name = name.substring(0, index) + ".tql.gz";
                                        output = outBase.resolve(name);
                                        if (java.nio.file.Files.exists(output)) {
                                            LOGGER.info("Skipping {}", path);
                                            succeeded.incrementAndGet();
                                            tracker.increment();
                                            continue;
                                        }
                                    } catch (final Throwable ex) {
                                        LOGGER.error("Could not compute output file name", ex);
                                    }
                                }

                                LOGGER.info("Processing {}", path);

                                try {
                                    final KAFDocument document = Runner.this.corpus.get(i);
                                    docName = document.getPublic().publicId;
                                    MDC.put("context", docName);
                                    filter.filter(document);
                                    final RDFSource source = RDFSources
                                            .wrap(Runner.this.generator.generate(document, null));

                                    if (!Runner.this.intermediate) {
                                        source.emit(RDFHandlers.ignoreMethods(writer,
                                                RDFHandlers.METHOD_START_RDF
                                                        | RDFHandlers.METHOD_END_RDF
                                                        | RDFHandlers.METHOD_CLOSE),
                                                1);
                                    } else {
                                        java.nio.file.Files.createDirectories(output.getParent());
                                        source.emit(RDFHandlers.write(null, 1,
                                                output.toAbsolutePath().toString()), 1);
                                    }

                                    succeeded.incrementAndGet();

                                } catch (final Throwable ex) {
                                    LOGGER.error("Processing failed for " + docName, ex);
                                } finally {
                                    MDC.remove("context");
                                }
                                tracker.increment();
                            }
                        } finally {
                            latch.countDown();
                        }
                    }

                });
            }
            try {
                latch.await();
                if (!this.intermediate) {
                    writer.endRDF();
                }
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (final RDFHandlerException ex) {
                throw new RuntimeException(ex);
            }
            tracker.end();

            LOGGER.info("Successfully converted {}/{} files", succeeded, this.corpus.size());
        }
    }

    private final class Extractor {

        private final String baseIRI;

        private final RDFHandler handler;

        private final QuadModel statements;

        private final BiMap<String, String> mintedIRIs;

        private final KAFDocument document;

        private final IRI documentIRI;

        private final boolean[] sentenceIDs;

        private final String documentText;

        private final Map<String, Annotation> annotations;

        public Extractor(final String baseIRI, final RDFHandler handler,
                final KAFDocument document, final boolean[] sentenceIDs) {

            this.baseIRI = baseIRI;
            this.handler = handler;
            this.statements = QuadModel.create();
            this.mintedIRIs = HashBiMap.create();
            this.document = document;
            this.documentIRI = FACTORY.createIRI(Util.cleanIRI(document.getPublic().uri));
            this.sentenceIDs = sentenceIDs;

            final StringBuilder builder = new StringBuilder();
            for (final WF word : document.getWFs()) {
                final int offset = word.getOffset();
                if (builder.length() > offset) {
                    builder.setLength(offset);
                } else {
                    while (builder.length() < offset) {
                        builder.append(" ");
                    }
                }
                builder.append(word.getForm());
            }
            this.documentText = builder.toString();

            this.annotations = Maps.newHashMap();
        }

        public void run() throws RDFHandlerException {

            // 0. Process NAF metadata
            processMetadata();

            // 1. Process <timex3> annotations
            for (final Timex3 timex : this.document.getTimeExs()) {
                if (timex.getSpan() == null
                        || this.sentenceIDs[timex.getSpan().getFirstTarget().getSent()]) {
                    try {
                        processTimex(timex);
                    } catch (final Throwable ex) {
                        LOGGER.error("Error processing " + NAFUtils.toString(timex) + ", type "
                                + timex.getType() + ", value " + timex.getValue(), ex);
                    }
                }
            }

            // 2. Process <entity> annotations
            for (final Entity entity : this.document.getEntities()) {
                for (final Span<Term> span : entity.getSpans()) {
                    if (this.sentenceIDs[span.getFirstTarget().getSent()]) {
                        try {
                            processEntity(entity);
                        } catch (final Throwable ex) {
                            LOGGER.error("Error processing " + NAFUtils.toString(entity)
                                    + ", type " + entity.getType(), ex);
                        }
                        break; // move to next entity
                    }
                }
            }

            // 3. Process <predicate> annotations; must be done after 1, 2
            outer: for (final Predicate predicate : this.document.getPredicates()) {
                if (this.sentenceIDs[predicate.getSpan().getFirstTarget().getSent()]) {
                    // TODO: the code below is madness... :-(
                    for (final ExternalRef ref : predicate.getExternalRefs()) {
                        if (NAFUtils.RESOURCE_PROPBANK.equals(ref.getResource())
                                && ref.getReference().equals("be.01")) {
                            Term a1Head = null;
                            Term a2Head = null;
                            for (final Role role : predicate.getRoles()) {
                                final Term head = NAFUtils.extractHead(this.document,
                                        role.getSpan());
                                if (head != null) {
                                    if ("A1".equals(role.getSemRole())) {
                                        a1Head = head;
                                    } else if ("A2".equals(role.getSemRole())) {
                                        a2Head = head;
                                    }
                                }
                            }
                            if (a1Head != null && a2Head != null) {
                                for (final Coref coref : this.document.getCorefsByTerm(a1Head)) {
                                    final Set<Term> corefHeads = Sets.newHashSet();
                                    for (final Span<Term> span : coref.getSpans()) {
                                        final Term head = NAFUtils.extractHead(this.document,
                                                span);
                                        if (head != null) {
                                            corefHeads.add(head);
                                        }
                                    }
                                    if (corefHeads.contains(a1Head)
                                            && corefHeads.contains(a2Head)) {
                                        continue outer;
                                    }
                                }
                            }
                        }
                    }
                    try {
                        processPredicate(predicate);
                    } catch (final Throwable ex) {
                        LOGGER.error("Error processing " + NAFUtils.toString(predicate), ex);
                    }
                }
            }

            // 4. Process <factvalue> annotations; must be done after 3
            for (final Factuality factuality : this.document.getFactualities()) {
                if (this.sentenceIDs[factuality.getWord().getSent()]) {
                    try {
                        processFactuality(factuality);
                    } catch (final Throwable ex) {
                        LOGGER.error("Error processing " + NAFUtils.toString(factuality), ex);
                    }
                }
            }

            // 5. Process <term> acting as modifiers; must be done after 1, 2, 3
            for (final Annotation ann : this.annotations.values()) {
                final IRI uri = ann.predicateIRI != null ? ann.predicateIRI : ann.objectIRI;
                if (uri != null) {
                    final Set<Term> forbiddenTerms = Sets.newHashSet();
                    final List<Coref> corefs = this.document.getCorefsByTerm(ann.head);
                    for (final Coref coref : corefs) {
                        final List<Term> heads = Lists.newArrayList();
                        for (final Span<Term> span : coref.getSpans()) {
                            final Term head = NAFUtils.extractHead(this.document, span);
                            if (head != null) {
                                heads.add(head);
                            }
                        }
                        if (heads.contains(ann.head)) {
                            forbiddenTerms.addAll(heads);
                        }
                    }
                    for (final Term term : this.document.getTermsByDepAncestors(
                            Collections.singleton(ann.head), MODIFIER_REGEX)) {
                        if (!forbiddenTerms.contains(term)) {
                            try {
                                processModifier(term, ann.head, uri, ann.extent);
                            } catch (final Throwable ex) {
                                LOGGER.error("Error processing MODIFIER " + NAFUtils.toString(term)
                                        + " of " + NAFUtils.toString(ann.head) + " (object IRI "
                                        + ann.objectIRI + "; predicate IRI " + ann.predicateIRI
                                        + ")", ex);
                            }
                        }
                    }
                }
            }

            // 6. Process <coref> annotations; must be done after 1, 2, 3
            for (final Coref coref : this.document.getCorefs()) {
                if ("event".equalsIgnoreCase(coref.getType())) {
                    continue;
                }
                final List<Span<Term>> spans = Lists.newArrayList();
                for (final Span<Term> span : coref.getSpans()) {
                    if (this.sentenceIDs[span.getFirstTarget().getSent()]) {
                        spans.add(span);
                    }
                }
                if (!spans.isEmpty()) {
                    try {
                        processCoref(spans);
                    } catch (final Throwable ex) {
                        LOGGER.error("Error processing " + NAFUtils.toString(coref), ex);
                    }
                }
            }

            // 7. Process head <term>s in <role> annotations; must be done after 1, 2, 3
            for (final Predicate predicate : this.document.getPredicates()) {
                if (this.sentenceIDs[predicate.getSpan().getFirstTarget().getSent()]) {
                    final PropBank.Roleset rs = PropBank
                            .getRoleset(NAFUtils.getRoleset(predicate));
                    final String entitySuffix = rs == null ? "?"
                            : Integer.toString(rs.getCoreferenceEntityArg());
                    final String predicateSuffix = rs == null ? "?"
                            : Integer.toString(rs.getCoreferencePredicateArg());
                    Set<Term> corefEntityHeads = null;
                    Set<Term> corefPredicateHeads = null;
                    for (final Role role : predicate.getRoles()) {
                        final Term roleHead = NAFUtils.extractHead(this.document, role.getSpan());
                        if (roleHead != null) {
                            final Set<Term> argHeads = this.document.getTermsByDepAncestors(
                                    Collections.singleton(roleHead), PARTICIPATION_REGEX);
                            boolean isCorefPredicateRole = false;
                            if (role.getSemRole().endsWith(entitySuffix)) {
                                corefEntityHeads = argHeads;
                            } else if (role.getSemRole().endsWith(predicateSuffix)) {
                                corefPredicateHeads = argHeads;
                                isCorefPredicateRole = true;
                            }
                            for (final Term argHead : argHeads) {
                                try {
                                    processRole(predicate, role, argHead, isCorefPredicateRole);
                                } catch (final Throwable ex) {
                                    LOGGER.error("Error processing " + NAFUtils.toString(role)
                                            + " of " + NAFUtils.toString(predicate) + ", argument "
                                            + NAFUtils.toString(argHead), ex);
                                }
                            }
                        }
                    }
                    if (corefEntityHeads != null && corefEntityHeads.size() == 1
                            && corefPredicateHeads != null && corefPredicateHeads.size() == 1) {
                        final Annotation entityAnn = this.annotations
                                .get(corefEntityHeads.iterator().next().getId());
                        final Annotation predicateAnn = this.annotations
                                .get(corefPredicateHeads.iterator().next().getId());
                        if (predicateAnn != null && entityAnn != null
                                && predicateAnn.predicateIRI != null
                                && predicateAnn.objectIRI != null && entityAnn.objectIRI != null) {
                            final IRI mentionIRI = emitMention(
                                    Iterables.concat(predicateAnn.extent, entityAnn.extent));
                            emitFact(predicateAnn.objectIRI, OWL.SAMEAS, entityAnn.objectIRI,
                                    mentionIRI, null);
                        }
                    }
                }
            }

            // 8. Process <opinion>s; must be done after 1, 2, 3
            for (final Opinion opinion : this.document.getOpinions()) {
                if (opinion.getOpinionExpression() == null || opinion.getLabel() != null
                        && (opinion.getLabel().toLowerCase().contains("stanford")
                                || opinion.getLabel().toLowerCase().contains("gold"))) {
                    continue;
                }
                for (final Term term : opinion.getOpinionExpression().getTerms()) {
                    if (this.sentenceIDs[term.getSent()]) {
                        processOpinion(opinion);
                        break;
                    }
                }
            }

            // 9. Finalize
            Iterable<Statement> statements = RDFGenerator.this.merging ? merge(this.statements)
                    : this.statements;
            if (RDFGenerator.this.normalization) {
                statements = new ProcessorASNorm("fact:").wrap(RDFSources.wrap(statements));
            }
            this.handler.startRDF();
            for (final Statement statement : statements) {
                this.handler.handleStatement(statement);
            }
            this.handler.endRDF();
        }

        private void processMetadata() throws RDFHandlerException {

            // Obtain IRIs of document and NAF resources
            final IRI docIRI = this.documentIRI;
            final IRI nafIRI = FACTORY.createIRI(docIRI.stringValue() + ".naf");

            // Emit document types
            emitMeta(docIRI, RDF.TYPE, new IRI[] { KS_OLD.RESOURCE, KS_OLD.TEXT });

            // Emit title, author and DCT from the <fileDesc> element, if present
            if (this.document.getFileDesc() != null) {
                final FileDesc fd = this.document.getFileDesc();
                emitMeta(docIRI, DCTERMS.TITLE, fd.title);
                emitMeta(docIRI, DCTERMS.CREATOR, fd.author);
                emitMeta(docIRI, DCTERMS.CREATED, fd.creationtime);
                emitMeta(docIRI, KS_OLD.NAF_FILE_NAME, fd.filename);
                emitMeta(docIRI, KS_OLD.NAF_FILE_TYPE, fd.filetype);
                emitMeta(docIRI, KS_OLD.NAF_PAGES, fd.pages);
            }

            // Emit the document language, if available
            if (this.document.getLang() != null) {
                emitMeta(docIRI, DCTERMS.LANGUAGE,
                        ModelUtil.languageCodeToIRI(this.document.getLang()));
            }

            // Emit an hash of the whitespace-normalized raw text, if available
            if (this.document.getRawText() != null) {
                final String rawText = this.document.getRawText();
                final StringBuilder builder = new StringBuilder();
                boolean addSpace = false;
                for (int i = 0; i < rawText.length(); ++i) {
                    final char c = rawText.charAt(i);
                    if (Character.isWhitespace(c)) {
                        addSpace = builder.length() > 0;
                    } else {
                        if (addSpace) {
                            builder.append(' ');
                            addSpace = false;
                        }
                        builder.append(c);
                    }
                }
                emitMeta(docIRI, KS_OLD.TEXT_HASH, Hash.murmur3(builder.toString()).toString());
            }

            // Link document to its NAF annotation
            emitMeta(docIRI, KS_OLD.ANNOTATED_WITH, nafIRI);
            emitMeta(nafIRI, KS_OLD.ANNOTATION_OF, docIRI);

            // Emit types, version and publicId of NAF resource
            emitMeta(nafIRI, RDF.TYPE, new IRI[] { KS_OLD.RESOURCE, KS_OLD.NAF });
            emitMeta(nafIRI, KS_OLD.VERSION, this.document.getVersion());
            emitMeta(nafIRI, DCTERMS.IDENTIFIER, this.document.getPublic().publicId);

            // Emit information about linguistic processors: dct:created, dct:creatro, ego:layer
            String timestamp = null;
            for (final Map.Entry<String, List<LinguisticProcessor>> entry : this.document
                    .getLinguisticProcessors().entrySet()) {
                emitMeta(nafIRI, KS_OLD.LAYER,
                        FACTORY.createIRI(KS_OLD.NAMESPACE, "layer_" + entry.getKey()));
                for (final LinguisticProcessor lp : entry.getValue()) {
                    if (timestamp == null) {
                        if (!Strings.isNullOrEmpty(lp.getBeginTimestamp())) {
                            timestamp = lp.getBeginTimestamp();
                        } else if (!Strings.isNullOrEmpty(lp.getEndTimestamp())) {
                            timestamp = lp.getEndTimestamp();
                        }
                    }
                    final IRI lpIRI = FACTORY.createIRI(ModelUtil
                            .cleanIRI(KS_OLD.NAMESPACE + lp.getName() + '.' + lp.getVersion()));
                    emitMeta(nafIRI, DCTERMS.CREATOR, lpIRI);
                    emitMeta(lpIRI, DCTERMS.TITLE, lp.getName());
                    emitMeta(lpIRI, KS_OLD.VERSION, lp.getVersion());
                }
            }
            emitMeta(nafIRI, DCTERMS.CREATED, timestamp);
        }

        private void processTimex(final Timex3 timex) throws RDFHandlerException {

            // Abort if timex has no span (e.g., the DCT)
            if (timex.getSpan() == null) {
                return;
            }

            // Extract terms, head and label
            final List<Term> terms = this.document.getTermsByWFs(timex.getSpan().getTargets());
            final Term head = NAFUtils.extractHead(this.document, KAFDocument.newTermSpan(terms));
            final String label = NAFUtils.getText(NAFUtils.filterTerms(terms));
            final String type = timex.getType().trim().toLowerCase();

            // Annotate the term (or pickup the existing annotation)
            final Annotation ann = defineAnnotation(head, terms);

            // Abort if cannot annotate (wrong head) or if a IRI was already assigned to the term
            if (ann == null || ann.objectIRI != null) {
                return;
            }

            // Emit a mention and its triples for the current timex
            final IRI mentionIRI = emitMention(terms);
            emitMeta(mentionIRI, RDF.TYPE, KS_OLD.TIME_MENTION);

            // Emit type specific statements
            IRI timexIRI = null;
            if (timex.getValue() != null) {
                if (type.equals("date") || type.equals("time")) {
                    final OWLTime.Interval interval = OWLTime.Interval
                            .parseTimex(timex.getValue());
                    if (interval != null) {
                        timexIRI = interval.toRDF(this.handler, RDFGenerator.this.owltimeNamespace,
                                null);
                    } else {
                        LOGGER.debug("Could not represent date/time value '" + timex.getValue()
                                + "' of " + NAFUtils.toString(timex));
                    }
                } else if (type.equals("duration")) {
                    final OWLTime.Duration duration = OWLTime.Duration
                            .parseTimex(timex.getValue());
                    if (duration != null) {
                        timexIRI = FACTORY.createIRI(RDFGenerator.this.owltimeNamespace,
                                duration.toString());
                        final IRI durationIRI = duration.toRDF(this.handler,
                                RDFGenerator.this.owltimeNamespace, null);
                        emitFact(timexIRI, OWLTIME.HAS_DURATION_DESCRIPTION, durationIRI,
                                mentionIRI, null);
                    } else {
                        LOGGER.debug("Could not represent duration value '" + timex.getValue()
                                + "' of " + NAFUtils.toString(timex));
                    }
                } else {
                    // TODO: support SET?
                    throw new UnsupportedOperationException("Unsupported TIMEX3 type: " + type);
                }
            }

            // Generate a default timex IRI on failure
            if (timexIRI == null) {
                timexIRI = mintIRI(timex.getId(),
                        MoreObjects.firstNonNull(timex.getValue(), timex.getSpan().getStr()));
            }

            // Register the timex IRI it in the term annotation and link it to the mention
            ann.objectIRI = timexIRI;
            emitMeta(timexIRI, GAF.DENOTED_BY, mentionIRI);

            // Emit common attributes based on head and label
            emitFact(timexIRI, RDF.TYPE,
                    ImmutableList.of(KS_OLD.ENTITY, KS_OLD.TIME, "timex." + type), mentionIRI,
                    null);
            emitCommonAttributes(timexIRI, mentionIRI, head, label, true);
        }

        private void processEntity(final Entity entity) throws RDFHandlerException {

            // Retrieve terms, head and label
            final List<Term> terms = entity.getSpans().get(0).getTargets();
            final String label = NAFUtils.getText(NAFUtils.filterTerms(terms));
            final Term head = NAFUtils.extractHead(this.document, entity.getSpans().get(0));
            if (head == null) {
                return;
            }

            // Extract type information (type IRI, whether timex or attribute) based on NER tag
            String type = entity.getType();
            type = type == null ? null : type.toLowerCase();
            // final Collection<IRI> typeIRIs = RDFGenerator.this.typeMap.get("entity." + type);
            final boolean isLinked = !entity.getExternalRefs().isEmpty();
            final boolean isProperty = "money".equals(type) || "cardinal".equals(type)
                    || "ordinal".equals(type) || "percent".equals(type) || "language".equals(type)
                    || "norp".equals(type) || "quantity".equals(type);

            // Discard attributes in modifier position, as they will be considered later
            final Dep dep = this.document.getDepToTerm(head);
            if (isProperty && dep != null) {
                final String depLabel = dep.getRfunc().toUpperCase();
                if (depLabel.contains("NMOD") || depLabel.contains("AMOD")) {
                    return;
                }
            }

            // Annotate the term (or pickup the existing annotation)
            final Annotation ann = defineAnnotation(head, terms);

            // Abort if cannot annotate (wrong head) or if a IRI was already assigned to the term
            if (ann == null || ann.objectIRI != null) {
                return;
            }

            // Mint a IRI for the entity and register it in the term annotation
            final IRI entityIRI;
            if (!entity.isNamed() || isLinked) {
                entityIRI = mintIRI(entity.getId(),
                        entity.isNamed() ? entity.getSpans().get(0).getStr() : head.getLemma());
            } else {
                entityIRI = Statements.VALUE_FACTORY.createIRI(Util
                        .cleanIRI("entity:" + entity.getStr().toLowerCase().replace(' ', '_')));
            }
            ann.objectIRI = entityIRI;

            // Emit a mention and its triples for the current entity
            final IRI mentionIRI = emitMention(terms);
            emitMeta(entityIRI, GAF.DENOTED_BY, mentionIRI);
            emitMeta(mentionIRI, RDF.TYPE, KS_OLD.ENTITY_MENTION);
            // if ("person".equals(type)) {
            // emitMeta(mentionIRI, RDF.TYPE, KS_OLD.PERSON_MENTION);
            // } else if ("organization".equals(type)) {
            // emitMeta(mentionIRI, RDF.TYPE, KS_OLD.ORGANIZATION_MENTION);
            // } else if ("location".equals(type)) {
            // emitMeta(mentionIRI, RDF.TYPE, KS_OLD.LOCATION_MENTION);
            // } else if (!isProperty) {
            // emitMeta(mentionIRI, RDF.TYPE, KS_OLD.MISC_MENTION);
            // }
            if (isProperty) {
                emitMeta(mentionIRI, RDF.TYPE, KS_OLD.ATTRIBUTE_MENTION);
            }

            // Emit common attributes based on head and label
            emitFact(entityIRI, RDF.TYPE, new Object[] { KS_OLD.ENTITY, "entity",
                    type == null ? null : "entity." + type }, mentionIRI, null);
            if (this.document.getPredicatesByTerm(head).isEmpty()) {
                emitCommonAttributes(entityIRI, mentionIRI, head, label, true);
            }

            // Handle the case the <entity> is an attribute of some anonymous instance
            if (isProperty) {
                emitEntityAttributes(entity, entityIRI, mentionIRI);
            } else {

                // TODO: originally the following check was enforced
                // if (!typeIRIs.isEmpty()) {
                // }

                // Handle the case the <entity> is an ontological instance itself
                final boolean named = entity.isNamed() || "romanticism".equalsIgnoreCase(label)
                        || "operant conditioning chamber".equalsIgnoreCase(label); // TODO
                if (named) {
                    emitFact(entityIRI, FOAF.NAME, label, mentionIRI, null);
                    emitMeta(mentionIRI, RDF.TYPE, KS_OLD.NAME_MENTION);
                }
                final IRI property = named ? OWL.SAMEAS : RDFS.SEEALSO;
                for (final ExternalRef ref : entity.getExternalRefs()) {
                    try {
                        final IRI refIRI = FACTORY.createIRI(Util.cleanIRI(ref.getReference()));
                        emitFact(entityIRI, property, refIRI, mentionIRI,
                                (double) ref.getConfidence());
                    } catch (final Throwable ex) {
                        // ignore: not a IRI
                    }
                }
            }
        }

        private void processPredicate(final Predicate predicate) throws RDFHandlerException {

            // Retrieve terms, head and label
            final List<Term> terms = predicate.getSpan().getTargets();
            final String label = NAFUtils.getText(NAFUtils.filterTerms(terms));
            final Term head = NAFUtils.extractHead(this.document, predicate.getSpan());

            // Abort if predicate overlaps with timex or named / ordinal entity
            if (!this.document.getTimeExsByTerm(head).isEmpty()) {
                return;
            }
            for (final Entity entity : this.document.getEntitiesByTerm(head)) {
                if (entity.isNamed() || "ordinal".equalsIgnoreCase(entity.getType())) {
                    return;
                }
            }

            // Annotate the term (or pickup the existing annotation); abort if wrong head
            final Annotation ann = defineAnnotation(head, terms);
            if (ann == null) {
                return;
            }

            // Validate the existing annotation based on expected previous processing
            if (ann.predicateIRI != null) {
                LOGGER.warn("Already processed: " + NAFUtils.toString(predicate) + "; head is "
                        + NAFUtils.toString(head));
                return; // this is a problem of the NAF
            }

            // Determine whether the predicate admit its own span as an argument
            boolean selfArg = false;
            if (ann.objectIRI != null) {
                for (final Role role : predicate.getRoles()) {
                    selfArg |= head.equals(NAFUtils.extractHead(this.document, role.getSpan()));
                }
            }

            // Determine if the predicate is an event, based on SUMO mapping
            boolean isEvent = false;
            for (final ExternalRef ref : head.getExternalRefs()) {
                if ("SUMO".equals(ref.getResource())) {
                    final IRI conceptIRI = SimpleValueFactory.getInstance()
                            .createIRI(SUMO.NAMESPACE, ref.getReference());
                    if (Sumo.isSubClassOf(conceptIRI, SUMO.PROCESS)) {
                        isEvent = true;
                        break;
                    }
                }
            }

            // Assign a IRI to the predicate, possibly reusing the IRI of an entity
            final IRI predicateIRI = ann.objectIRI != null && !selfArg ? ann.objectIRI
                    : mintIRI(predicate.getId(), head.getLemma());
            ann.predicateIRI = predicateIRI;

            // Emit a mention and its triples (reuse an entity span if possible)
            IRI mentionIRI = null;
            if (predicateIRI.equals(ann.objectIRI)) {
                for (final Entity entity : this.document.getEntitiesByTerm(head)) {
                    mentionIRI = emitMention(entity.getSpans().get(0).getTargets());
                }
            } else {
                mentionIRI = emitMention(terms);
            }
            emitMeta(mentionIRI, RDF.TYPE, KS_OLD.PREDICATE_MENTION);
            emitMeta(predicateIRI, GAF.DENOTED_BY, mentionIRI);

            // Emit common attributes
            if (ann.objectIRI == null) {
                emitCommonAttributes(ann.predicateIRI, mentionIRI, head, label, true);
            } else {
                emitCommonAttributes(ann.objectIRI, mentionIRI, head, label, !selfArg);
            }

            // Process framenet/verbnet/etc external refs
            for (final ExternalRef ref : predicate.getExternalRefs()) {
                if ("".equals(ref.getReference())) {
                    continue;
                }
                final IRI typeIRI = mintRefIRI(ref.getResource(), ref.getReference());
                emitFact(predicateIRI, RDF.TYPE, typeIRI, mentionIRI, null);
                // if (ref.getResource().equals(NAFUtils.RESOURCE_FRAMENET)) {
                // for (final String id : FrameNet.getRelatedFrames(true, ref.getReference(),
                // FrameNet.Relation.INHERITS_FROM)) {
                // final IRI uri = mintRefIRI(NAFUtils.RESOURCE_FRAMENET, id);
                // emitFact(predicateIRI, RDF.TYPE, uri, mentionIRI, null);
                // }
                // } else if (ref.getResource().equals(NAFUtils.RESOURCE_VERBNET)) {
                // for (final String id : VerbNet.getSuperClasses(true, ref.getReference())) {
                // final IRI uri = mintRefIRI(NAFUtils.RESOURCE_VERBNET, id);
                // emitFact(predicateIRI, RDF.TYPE, uri, mentionIRI, null);
                // }
                // }
            }

            // Mark the predicate as sem:Event and associate it the correct ego: type
            final List<Object> typeKeys = Lists.newArrayList(KS_OLD.ENTITY, KS_OLD.PREDICATE,
                    SEM.EVENT);
            if (isEvent) {
                typeKeys.add(SUMO.PROCESS);
            }
            emitFact(predicateIRI, RDF.TYPE, typeKeys, mentionIRI, null);
        }

        private void processFactuality(final Factuality factuality) throws RDFHandlerException {

            // TODO: factuality should be better handled

            // Retrieve term and corresponding annotation
            final Term term = factuality.getWord();
            final Annotation ann = this.annotations.get(term.getId());

            // Abort if the annotation is missing or does not refer to a predicate
            if (ann == null || ann.predicateIRI == null) {
                return;
            }

            // Emit a mention for the predicate extent
            final IRI mentionIRI = emitMention(ann.extent);

            // Emit a triple associating the factuality value to the predicate
            final String value = factuality.getMaxPart().getPrediction();
            emitFact(ann.predicateIRI, KS_OLD.FACTUALITY, value, mentionIRI, null);
        }

        private void processModifier(final Term modifierTerm, final Term instanceTerm,
                final IRI instanceIRI, final List<Term> instanceExtent)
                throws RDFHandlerException {

            // Retrieve POS and <entity> corresponding to the modifier term
            final char pos = Character.toUpperCase(modifierTerm.getPos().charAt(0));
            final List<Entity> entities = this.document.getEntitiesByTerm(modifierTerm);
            final Annotation ann = this.annotations.get(modifierTerm.getId());

            // Ignore modifiers marked as TIMEX
            if (!this.document.getTimeExsByTerm(modifierTerm).isEmpty()) {
                return;
            }

            if (ann != null) {
                // If modifier has been mapped to some other instance, link the two instances
                final IRI otherIRI = ann.objectIRI != null ? ann.objectIRI : ann.predicateIRI;
                if (otherIRI != null) {
                    final IRI mentionID = emitMention(
                            Iterables.concat(instanceExtent, ann.extent));
                    emitFact(instanceIRI, KS_OLD.MOD, otherIRI, mentionID, null);
                }
                final String path = extractPath(instanceTerm, modifierTerm);
                if (!Strings.isNullOrEmpty(path)) {
                    final IRI mentionID = emitMention(
                            Iterables.concat(instanceExtent, ann.extent));
                    final IRI property = mintRefIRI("conn", path);
                    emitFact(instanceIRI, property, otherIRI, mentionID, null);
                }

            } else if (!entities.isEmpty()) {
                // If modifier is an <entity> for which we didn't create a node, then create
                // an attribute and attach it to the modified entity
                final Entity entity = entities.get(0);
                final IRI mentionIRI = emitMention(entity.getSpans().get(0).getTargets());
                emitMeta(mentionIRI, RDF.TYPE, KS_OLD.ATTRIBUTE_MENTION);
                emitEntityAttributes(entity, instanceIRI, mentionIRI);

            } else if (pos == 'G' || pos == 'A' || pos == 'V') {
                // WAS AT THE BEGINNING
                // If modifier is an adjective, noun, pronoun or verb, then attach a
                // 'quality' attribute to the modified node
                final Set<Term> terms = this.document.getTermsByDepAncestors(
                        Collections.singleton(modifierTerm), "(AMOD|NMOD)*");
                final IRI mentionIRI = emitMention(terms);
                final IRI expressionIRI = emitTerm(modifierTerm);
                emitFact(instanceIRI, KS_OLD.MOD, expressionIRI, mentionIRI, null);
            }
        }

        private void processCoref(final List<Span<Term>> spans) throws RDFHandlerException {

            // Build three correlated lists containing, for each member of the coref cluster, its
            // span, the head terms of instances in the span and the associated IRIs
            final List<Span<Term>> corefSpans = Lists.newArrayList();
            final List<List<Term>> corefTerms = Lists.newArrayList();
            final List<List<Term>> corefExtents = Lists.newArrayList();
            final List<List<IRI>> corefIRIs = Lists.newArrayList();
            for (final Span<Term> span : spans) {
                final Term head = NAFUtils.extractHead(this.document, span);
                if (head != null) {
                    final List<Term> terms = Lists.newArrayList();
                    final List<IRI> uris = Lists.newArrayList();
                    final Set<Term> extent = Sets.newHashSet();
                    for (final Term term : this.document.getTermsByDepAncestors(
                            Collections.singleton(head), "(COORD CONJ?)*")) {
                        if (!span.getTargets().contains(term)) {
                            continue;
                        }
                        final Annotation ann = this.annotations.get(term.getId());
                        final IRI uri = ann == null ? null
                                : ann.objectIRI != null ? ann.objectIRI : ann.predicateIRI;
                        if (uri != null) {
                            terms.add(term);
                            uris.add(uri);
                            extent.addAll(ann.extent);
                        }
                    }
                    if (!terms.isEmpty()) {
                        corefSpans.add(span);
                        corefTerms.add(terms);
                        corefExtents.add(Ordering.natural().immutableSortedCopy(extent));
                        corefIRIs.add(uris);
                    }
                }
            }

            // Abort in case there is only one member in the coref cluster
            if (corefTerms.size() <= 1) {
                return;
            }

            // Map each coref member to a term / IRI pair, possibly grouping coordinated instances
            // in a compound instance via a ego:Composition relation
            final Map<Term, IRI> members = Maps.newHashMap();
            final Map<Term, Span<Term>> memberSpans = Maps.newHashMap();
            for (int i = 0; i < corefTerms.size(); ++i) {
                final Span<Term> span = corefSpans.get(i);
                final List<Term> terms = corefTerms.get(i);
                final List<Term> extent = corefExtents.get(i);
                final List<IRI> uris = corefIRIs.get(i);
                memberSpans.put(terms.get(0), span);
                if (terms.size() == 1) {
                    members.put(terms.get(0), uris.get(0));
                } else {
                    final StringBuilder builder = new StringBuilder();
                    for (final IRI uri : uris) {
                        builder.append(builder.length() == 0 ? "" : "_");
                        builder.append(uri.getLocalName());
                    }
                    final IRI compIRI = mintIRI(builder.toString(), null);
                    final IRI mentionIRI = emitMention(extent);
                    // final String label =
                    // NAFUtils.getText(NAFUtils.filterTerms(span.getTargets()));

                    // final IRI predIRI =
                    // this.emitter.mintIRI(builder.append("_pred").toString(),
                    // null);
                    // this.emitter.emitFact(predIRI, RDF.TYPE, new Object[] { KS_OLD.THING,
                    // KS_OLD.PREDICATE, SUMO.ENTITY, SEM.EVENT, "predicate.relation",
                    // KS_OLD.COMPOSITION }, mentionIRI, null);
                    // this.emitter.emitFact(compIRI, EGO.PLURAL, true, mentionIRI, null);
                    // this.emitter.emitMeta(mentionIRI, RDF.TYPE, KS_OLD.PREDICATE_MENTION);

                    emitFact(compIRI, RDF.TYPE, new Object[] { KS_OLD.ENTITY }, mentionIRI, null);
                    // emitFact(compIRI, RDFS.LABEL, label, mentionIRI, null);
                    // emitMeta(mentionIRI, RDF.TYPE, KS_OLD.MISC_MENTION);

                    // emitMeta(compIRI, GAF.DENOTED_BY, mentionIRI);

                    // this.emitter.emitFact(predIRI, KS_OLD.COMPOSITE, compIRI, mentionIRI,
                    // null);
                    for (int j = 0; j < uris.size(); ++j) {
                        // this.emitter
                        // .emitFact(predIRI, KS_OLD.COMPONENT, uris.get(j), mentionIRI, null);
                        emitFact(compIRI, KS_OLD.INCLUDE, uris.get(j), mentionIRI, null);
                    }
                    members.put(terms.get(0), compIRI);
                }
            }

            // Emit all possible coreference relations between cluster members
            for (final Map.Entry<Term, IRI> entry1 : members.entrySet()) {
                for (final Map.Entry<Term, IRI> entry2 : members.entrySet()) {
                    final Term term1 = entry1.getKey();
                    final Term term2 = entry2.getKey();
                    if (term1.getId().compareTo(term2.getId()) < 0) {
                        final Span<Term> span1 = memberSpans.get(term1);
                        final Span<Term> span2 = memberSpans.get(term2);
                        final IRI mentionIRI = emitMention(
                                Iterables.concat(span1.getTargets(), span2.getTargets()));
                        final IRI uri1 = entry1.getValue();
                        final IRI uri2 = entry2.getValue();
                        // final int distance = Math.abs(term1.getSent() - term2.getSent());
                        emitFact(uri1, OWL.SAMEAS, uri2, mentionIRI, null);
                    }
                }
            }
        }

        private void processRole(final Predicate predicate, final Role role, final Term argHead,
                final boolean isCorefPredicateRole) throws RDFHandlerException {

            // Retrieve the IRI previously associated to the predicate; abort if not found
            final Term predHead = NAFUtils.extractHead(this.document, predicate.getSpan());
            final Annotation predAnn = this.annotations.get(predHead.getId());
            final IRI predIRI = predAnn == null ? null : predAnn.predicateIRI;
            if (predIRI == null) {
                return;
            }

            // Retrieve the IRI previously associated to the argument, if any
            IRI argIRI = null;
            final Annotation argAnn = this.annotations.get(argHead.getId());
            if (argAnn != null) {
                if (argAnn.predicateIRI != null
                        && (argAnn.objectIRI == null || isCorefPredicateRole)) {
                    argIRI = argAnn.predicateIRI;
                } else {
                    argIRI = argAnn.objectIRI;
                }
            }

            // Discard invalid arguments (arg = pred, no arg IRI and arg not noun, adj, adv)
            final char pos = Character.toUpperCase(argHead.getPos().charAt(0));
            if (argIRI != null && argIRI.equals(predIRI)
                    || argIRI == null && pos != 'N' && pos != 'G' && pos != 'A') {
                return;
            }

            // Determine the participation properties, starting with ego:argument
            final Set<IRI> properties = Sets.newHashSet();

            // Add properties from the SEM ontology
            String semRole = role.getSemRole();
            if (semRole != null && !semRole.equals("")) {

                // TODO Drop R-AX
                if (semRole.startsWith("R-")) {
                    return;
                }

                semRole = semRole.toLowerCase();
                final int index = semRole.lastIndexOf('-');
                if (index >= 0) {
                    semRole = semRole.substring(index + 1);
                }
                if (Character.isDigit(semRole.charAt(semRole.length() - 1))) {
                    semRole = semRole.substring(semRole.length() - 1);
                    properties.add(SEM.HAS_ACTOR);
                } else if (semRole.equals("tmp")) {
                    properties.add(SEM.HAS_TIME);
                } else if (semRole.equals("loc")) {
                    properties.add(SEM.HAS_PLACE);
                }
            }

            // Determine the resource (propbank/nombank) to use for interpreting the sem role
            final String semRoleResource = predHead.getPos().equalsIgnoreCase("V") ? "propbank"
                    : "nombank";

            // Add properties from ProbBank, NomBank, VerbNet, FrameNet
            for (final ExternalRef ref : role.getExternalRefs()) {
                final String resource = ref.getResource().toLowerCase();
                final String name = ref.getReference().replace('#', '.');
                if (resource.equals(semRoleResource) || name.equals("")) {
                    continue;
                }
                // final int index = name.lastIndexOf('@');
                // final String arg = (index < 0 ? name : name.substring(index +
                // 1)).toLowerCase();
                //
                // if (resource.equalsIgnoreCase(NAFUtils.RESOURCE_FRAMENET)
                // || resource.equalsIgnoreCase(NAFUtils.RESOURCE_VERBNET) || index < 0) {
                // properties.add(mintRefIRI(resource, arg));
                // } else {
                // if (Character.isDigit(arg.charAt(0))) {
                // final String sense = name.substring(0, index);
                // properties.add(mintRefIRI(resource, sense + "_" + arg));
                // } else {
                // properties.add(mintRefIRI(resource, arg));
                // }
                // }
                properties.add(mintRefIRI(resource, name));
            }

            // The AX, AM-X information may not be encoded in external references, so
            // we derive it from predicate sense and role semRole property.
            if (!Strings.isNullOrEmpty(semRole)) {
                for (final ExternalRef ref : predicate.getExternalRefs()) {
                    final String resource = ref.getResource().toLowerCase();
                    if (resource.equals(semRoleResource)) {
                        if (Character.isDigit(semRole.charAt(0))) {
                            properties.add(mintRefIRI(resource,
                                    ref.getReference().toLowerCase() + "_" + semRole));
                        } else {
                            properties.add(mintRefIRI(resource, semRole));
                        }
                    }
                }
            }

            // Add path properties
            final String path = extractPath(predHead, argHead);
            if (path == null) {
                LOGGER.debug("Could not compute dependency path from " + predHead.getId() + " to "
                        + argHead.getId());
            }
            if (!Strings.isNullOrEmpty(path)) {
                properties.add(mintRefIRI("conn", path));
            }

            // Create either an edge or an attribute
            final List<Term> predTerms = predicate.getSpan().getTargets();
            if (argIRI != null) {
                final IRI mentionIRI = emitMention(Iterables.concat(predTerms, argAnn.extent));
                emitMeta(mentionIRI, RDF.TYPE, KS_OLD.PARTICIPATION_MENTION);
                for (final IRI property : properties) {
                    emitFact(predIRI, property, argIRI, mentionIRI, null);
                }
            } else {
                final Set<Term> argTerms = this.document
                        .getTermsByDepAncestors(Collections.singleton(argHead), "(AMOD|NMOD)*");
                final IRI mentionIRI = emitMention(Iterables.concat(predTerms, argTerms));
                emitMeta(mentionIRI, RDF.TYPE, KS_OLD.PARTICIPATION_MENTION);
                final IRI expressionIRI = emitTerm(argHead);
                for (final IRI property : properties) {
                    emitFact(predIRI, property, expressionIRI, mentionIRI, null);
                }
            }
        }

        private void processOpinion(final Opinion opinion) {

            // Identify the sentence where the opinion occurs (for normalization purposes)
            final int sentenceID = opinion.getOpinionExpression().getTerms().get(0).getSent();

            // Mint a IRI for the opinion and emit polarity and label facts
            final IRI opinionIRI = mintIRI(opinion.getId(), null);
            final Polarity polarity = Polarity.forExpression(opinion.getOpinionExpression());
            emitFact(opinionIRI, RDF.TYPE, SUMO.ENTITY, null, null);
            emitFact(opinionIRI, RDF.TYPE, KS_OLD.OPINION, null, null);
            emitFact(opinionIRI, RDF.TYPE,
                    polarity == Polarity.POSITIVE ? KS_OLD.POSITIVE_OPINION
                            : polarity == Polarity.NEGATIVE ? KS_OLD.NEGATIVE_OPINION
                                    : KS_OLD.NEUTRAL_OPINION,
                    null, null);
            if (opinion.getLabel() != null) {
                emitFact(opinionIRI, RDFS.LABEL, opinion.getLabel(), null, null);
            }

            // Emit links from opinion to its expression nodes
            final Span<Term> exprSpan = NAFUtils.trimSpan(opinion.getOpinionExpression().getSpan(),
                    sentenceID);
            final Set<Term> exprHeads = exprSpan == null ? ImmutableSet.<Term>of()
                    : NAFUtils.extractHeads(this.document, null, exprSpan.getTargets(),
                            NAFUtils.matchExtendedPos(this.document, "NN", "VB", "JJ", "R"));
            emitOpinionArgument(opinionIRI, null, KS_OLD.EXPRESSION, exprSpan, exprHeads);

            // Emit links from opinion to target nodes
            final OpinionTarget target = opinion.getOpinionTarget();
            final Span<Term> targetSpan = target == null ? null
                    : NAFUtils.trimSpan(target.getSpan(), sentenceID);
            final Set<Term> targetHeads = targetSpan == null ? ImmutableSet.<Term>of()
                    : NAFUtils.extractHeads(this.document, null, targetSpan.getTargets(),
                            NAFUtils.matchExtendedPos(this.document, "NN", "PRP", "JJP", "DTP",
                                    "WP", "VB"));
            emitOpinionArgument(opinionIRI, null, KS_OLD.TARGET, targetSpan, targetHeads);

            // Emit links from opinion to holder nodes
            final OpinionHolder holder = opinion.getOpinionHolder();
            final Span<Term> holderSpan = holder == null ? null
                    : NAFUtils.trimSpan(holder.getSpan(), sentenceID);
            final Set<Term> holderHeads = holderSpan == null ? ImmutableSet.<Term>of()
                    : NAFUtils.extractHeads(this.document, null, holderSpan.getTargets(), NAFUtils
                            .matchExtendedPos(this.document, "NN", "PRP", "JJP", "DTP", "WP"));
            emitOpinionArgument(opinionIRI, null, KS_OLD.HOLDER, holderSpan, holderHeads);
        }

        private void emitOpinionArgument(final IRI opinionID, @Nullable final IRI spanProperty,
                @Nullable final IRI headProperty, @Nullable final Span<Term> span,
                @Nullable final Set<Term> heads) {

            if (span != null) {
                outer: for (final Term term : span.getTargets()) {
                    final Annotation ann = this.annotations.get(term.getId());
                    IRI uri = ann == null ? null
                            : ann.objectIRI != null ? ann.objectIRI : ann.predicateIRI;
                    if (uri == null && "AGV".contains(term.getPos())) {
                        for (final Dep dep : this.document.getDepsFromTerm(term)) {
                            if (dep.getRfunc().equals("VC")) {
                                continue outer;
                            }
                        }
                        uri = emitTerm(term);
                    }
                    if (uri != null) {
                        if (spanProperty != null) {
                            emitFact(opinionID, spanProperty, uri, null, null);
                        }
                        if (headProperty != null && heads != null && heads.contains(term)) {
                            emitFact(opinionID, headProperty, uri, null, null);
                        }
                    }
                }
            }
        }

        private void emitCommonAttributes(final IRI instanceID, final IRI mentionID,
                final Term head, final String label, final boolean emitSumo)
                throws RDFHandlerException {

            if ("QPD".indexOf(head.getPos()) < 0 && label != null && !label.isEmpty()) {
                emitFact(instanceID, RDFS.LABEL, label, mentionID, null);
            }

            final char pos = Character.toUpperCase(head.getPos().charAt(0));
            if (pos == 'N' || pos == 'V') {
                emitMeta(mentionID, KS_OLD.LEMMA, head.getLemma());
                // this.emitter.emitFact(instanceID, EGO.LEMMA, head.getLemma(), mentionID, null);
            }

            final ExternalRef sstRef = NAFUtils.getRef(head, NAFUtils.RESOURCE_WN_SST, null);
            if (sstRef != null) {
                final String sst = sstRef.getReference();
                final IRI uri = FACTORY.createIRI("http://www.newsreader-project.eu/sst/",
                        sst.substring(sst.lastIndexOf('-') + 1));
                emitMeta(mentionID, KS_OLD.SST, uri);
                // this.emitter.emitFact(instanceID, EGO.SST, uri, mentionID, null);
            }

            final ExternalRef synsetRef = NAFUtils.getRef(head, NAFUtils.RESOURCE_WN_SYNSET, null);
            if (synsetRef != null) {
                final IRI uri = FACTORY.createIRI("http://www.newsreader-project.eu/syn/",
                        synsetRef.getReference());
                emitMeta(mentionID, KS_OLD.SYNSET, uri);
                // this.emitter.emitFact(instanceID, EGO.SYNSET, uri, mentionID, null);
            }

            final String p = head.getMorphofeat().toUpperCase();
            if (p.equals("NNS") || p.equals("NNPS")) {
                emitMeta(mentionID, KS_OLD.PLURAL, true);
                // this.emitter.emitFact(instanceID, EGO.PLURAL, true, mentionID, null);
            }

            for (final ExternalRef ref : head.getExternalRefs()) {
                final IRI typeIRI = mintRefIRI(ref.getResource(), ref.getReference());
                if (ref.getResource().equals(NAFUtils.RESOURCE_SUMO)) {
                    if (emitSumo) {
                        emitFact(instanceID, RDF.TYPE, typeIRI, mentionID, ref.getConfidence());
                        emitFact(instanceID, RDF.TYPE, Sumo.getSuperClasses(typeIRI), mentionID,
                                ref.getConfidence());
                    }
                } else {
                    emitFact(instanceID, RDF.TYPE, typeIRI, mentionID, ref.getConfidence());
                }
            }
        }

        private void emitEntityAttributes(final Entity entity, final IRI subject,
                final IRI mention) throws RDFHandlerException {

            // Retrieve normalized value and NER tag
            final ExternalRef valueRef = NAFUtils.getRef(entity, "value", null);
            String nerTag = entity.getType();
            nerTag = nerTag == null ? null : nerTag.toLowerCase();

            // For NORP and LANGUAGE entities we use the DBpedia IRIs from entity linking
            if (Objects.equal(nerTag, "norp") || Objects.equal(nerTag, "language")) {
                final IRI attribute = Objects.equal(nerTag, "norp") ? KS_OLD.PROVENANCE
                        : KS_OLD.LANGUAGE;
                for (final ExternalRef ref : entity.getExternalRefs()) {
                    try {
                        final IRI refIRI = FACTORY.createIRI(Util.cleanIRI(ref.getReference()));
                        emitFact(subject, attribute, refIRI, mention,
                                (double) ref.getConfidence());
                    } catch (final Throwable ex) {
                        // ignore: not a IRI
                    }
                }

            } else if (valueRef != null) {
                // Otherwise, we use the normalized value from Stanford
                try {
                    final String s = valueRef.getReference().trim();
                    if (s.isEmpty()) {
                        return;
                    }
                    if (Objects.equal(nerTag, "cardinal") || Objects.equal(nerTag, "quantity")) {
                        emitFact(subject, KS_OLD.QUANTITY, Double.parseDouble(s), mention, null);

                    } else if (Objects.equal(nerTag, "ordinal")) {
                        emitFact(subject, KS_OLD.RANK, Double.parseDouble(s), mention, null);

                    } else if (Objects.equal(nerTag, "percent")) {
                        final int index = s.indexOf('%');
                        emitFact(subject, KS_OLD.PERCENTAGE,
                                Double.parseDouble(s.substring(index + 1)), mention, null);

                    } else if (Objects.equal(nerTag, "money")) {
                        int index = 0;
                        while (index < s.length()) {
                            final char c = s.charAt(index);
                            if (c == '€') {
                                emitFact(subject, GR.HAS_CURRENCY, "EUR", mention, null);
                            } else if (c == '$') {
                                emitFact(subject, GR.HAS_CURRENCY, "USD", mention, null);
                            } else if (c == '¥') {
                                emitFact(subject, GR.HAS_CURRENCY, "YEN", mention, null);
                            } else if (Character.isDigit(c)) {
                                break;
                            }
                            ++index;
                        }
                        emitFact(subject, GR.HAS_CURRENCY_VALUE,
                                Double.parseDouble(s.substring(index)), mention, null);
                    }
                } catch (final NumberFormatException ex) {
                    LOGGER.debug("Could not process normalized value: " + valueRef.getReference());
                }
            }
        }

        @Nullable
        private IRI emitMention(final Iterable<Term> terms) {

            final List<Term> sortedTerms = Ordering.from(Term.OFFSET_COMPARATOR).sortedCopy(terms);
            final int numTerms = sortedTerms.size();
            if (numTerms == 0) {
                return null;
            }

            final String text = this.documentText;
            final List<IRI> componentIRIs = Lists.newArrayList();
            final int begin = NAFUtils.getBegin(sortedTerms.get(0));
            int offset = begin;
            int startTermIdx = 0;

            final StringBuilder anchorBuilder = new StringBuilder();
            final StringBuilder uriBuilder = new StringBuilder(this.documentIRI.stringValue())
                    .append("#char=").append(begin).append(",");

            for (int i = 0; i < numTerms; ++i) {
                final Term term = sortedTerms.get(i);
                final int termOffset = NAFUtils.getBegin(term);
                if (termOffset > offset && !text.substring(offset, termOffset).trim().isEmpty()) {
                    final int start = NAFUtils.getBegin(sortedTerms.get(startTermIdx));
                    anchorBuilder.append(text.substring(start, offset)).append(" [...] ");
                    uriBuilder.append(offset).append(";").append(termOffset).append(',');
                    componentIRIs.add(emitMention(sortedTerms.subList(startTermIdx, i)));
                    startTermIdx = i;
                }
                offset = NAFUtils.getEnd(term);
            }
            if (startTermIdx > 0) {
                componentIRIs.add(emitMention(sortedTerms.subList(startTermIdx, numTerms)));
            }
            anchorBuilder.append(
                    text.substring(NAFUtils.getBegin(sortedTerms.get(startTermIdx)), offset));
            uriBuilder.append(offset);

            final String anchor = anchorBuilder.toString();
            final IRI mentionID = FACTORY.createIRI(uriBuilder.toString());
            emitMeta(mentionID, KS_OLD.MENTION_OF, this.documentIRI);
            emitMeta(this.documentIRI, KS_OLD.HAS_MENTION, mentionID);
            emitMeta(mentionID, RDF.TYPE, KS_OLD.MENTION);
            if (!componentIRIs.isEmpty()) {
                emitMeta(mentionID, RDF.TYPE, KS_OLD.COMPOUND_STRING);
                for (final IRI componentIRI : componentIRIs) {
                    emitMeta(mentionID, KS_OLD.COMPONENT_SUB_STRING, componentIRI);
                }
            }
            emitMeta(mentionID, NIF.BEGIN_INDEX, FACTORY.createLiteral(begin));
            emitMeta(mentionID, NIF.END_INDEX, FACTORY.createLiteral(offset));
            emitMeta(mentionID, NIF.ANCHOR_OF, FACTORY.createLiteral(anchor));

            // Emit context of 3 sentences around the mention TODO
            // final int sentID = sortedTerms.get(0).getSent();
            // final List<Term> sentTerms = Lists.newArrayList();
            // for (int s = Math.max(1, sentID - 1); s <=
            // Math.min(this.document.getNumSentences(),
            // sentID + 1); ++s) {
            // sentTerms.addAll(this.document.getTermsBySent(s));
            // }
            // Collections.sort(sentTerms, Term.OFFSET_COMPARATOR);
            // final StringBuilder sentBuilder = new StringBuilder();
            // int sentOffset = -1;
            // boolean lastSelected = false;
            // for (final Term term : sentTerms) {
            // final boolean nextSelected = sortedTerms.contains(term);
            // if (!nextSelected && lastSelected) {
            // sentBuilder.append(" ]__ ");
            // }
            // if (sentOffset >= 0) {
            // for (int i = 0; i < term.getOffset() - sentOffset; ++i) {
            // sentBuilder.append(' ');
            // }
            // }
            // if (nextSelected && !lastSelected) {
            // sentBuilder.append(" __[ ");
            // }
            // sentBuilder.append(term.getStr());
            // sentOffset = term.getOffset() + term.getLength();
            // lastSelected = nextSelected;
            // }
            // emitMeta(mentionID, new IRIImpl(KS_OLD.NAMESPACE + "context"),
            // FACTORY.createLiteral(sentBuilder.toString()));

            return mentionID;
        }

        private IRI emitTerm(final Term head) {

            final ExternalRef synsetRef = NAFUtils.getRef(head, NAFUtils.RESOURCE_WN_SYNSET, null);
            final String headSynsetID = synsetRef == null ? null : synsetRef.getReference();
            final String readableHeadSynsetID = WordNet.getReadableSynsetID(headSynsetID);
            final String headID = MoreObjects.firstNonNull(readableHeadSynsetID, //
                    head.getLemma().toLowerCase());

            final List<IRI> modifierIRIs = Lists.newArrayList();
            final List<String> modifierIDs = Lists.newArrayList();

            for (final Term modifier : this.document.getTermsByDepAncestors(ImmutableSet.of(head),
                    "AMOD|NMOD")) {
                if ("AGV".contains(modifier.getPos())) {
                    final IRI modifierIRI = emitTerm(modifier);
                    modifierIRIs.add(modifierIRI);
                    modifierIDs.add(modifierIRI.getLocalName());
                }
            }

            final Set<Term> terms = this.document.getTermsByDepAncestors(ImmutableSet.of(head),
                    "(AMOD|NMOD)*");
            for (final Iterator<Term> i = terms.iterator(); i.hasNext();) {
                if (!"AGV".contains(i.next().getPos())) {
                    i.remove();
                }
            }
            final String label = NAFUtils.getText(NAFUtils.filterTerms(terms));

            final StringBuilder idBuilder = new StringBuilder();
            int level = 0;
            for (final String modifierID : modifierIDs) {
                for (int i = 1; modifierID.contains(Strings.repeat("_", i)); ++i) {
                    level = Math.max(level, i);
                }
            }
            final String separator = Strings.repeat("_", level + 1);
            for (final String modifierID : Ordering.natural().immutableSortedCopy(modifierIDs)) {
                idBuilder.append(modifierID).append(separator);
            }
            final String id = idBuilder.append(headID).toString();
            final IRI uri = mintRefIRI("attribute", id);
            // final IRI uri = this.emitter.mintIRI(id + "-" + head.getId(), id);

            emitFact(uri, RDF.TYPE, KS_OLD.ATTRIBUTE, null, null);
            emitFact(uri, RDFS.LABEL, label, null, null);
            if (headSynsetID != null) {
                emitFact(uri, KS_OLD.HEAD_SYNSET, mintRefIRI("syn", headSynsetID), null, null);
            }
            for (final IRI modifierIRI : modifierIRIs) {
                emitFact(uri, KS_OLD.MOD, modifierIRI, null, null);
            }

            final IRI mentionIRI = emitMention(terms);
            emitMeta(mentionIRI, RDF.TYPE, KS_OLD.ATTRIBUTE_MENTION);
            emitMeta(uri, GAF.DENOTED_BY, mentionIRI);

            return uri;
        }

        @Nullable
        private String extractPath(final Term from, final Term to) {

            final Set<Term> fromTerms = this.document
                    .getTermsByDepDescendants(ImmutableSet.of(from), "(-VC|-IM|-OPRD)*");
            final Set<Term> toTerms = this.document.getTermsByDepDescendants(ImmutableSet.of(to),
                    "(-VC|-IM|-OPRD)*");

            if (!Sets.intersection(fromTerms, toTerms).isEmpty()) {
                return null;
            }

            final List<Dep> path = this.document.getDepPath(from, to);
            if (path == null) {
                return null;
            }

            for (final Iterator<Dep> i = path.iterator(); i.hasNext();) {
                final Dep dep = i.next();
                if (fromTerms.contains(dep.getFrom()) && fromTerms.contains(dep.getTo())
                        || toTerms.contains(dep.getFrom()) && toTerms.contains(dep.getTo())) {
                    i.remove();
                }
            }

            if (fromTerms.contains(path.get(0).getTo())) {
                return null; // moving towards tree root
            }

            final StringBuilder builder = new StringBuilder();
            for (int i = 1; i < path.size(); ++i) {
                final Dep dep = path.get(i);
                final String func = dep.getRfunc();
                final Term term = dep.getFrom();
                if (!func.equalsIgnoreCase("COORD") && !func.equals("CONJ")) {
                    builder.append(builder.length() > 0 ? "_" : "")
                            .append(term.getLemma().toLowerCase().replace(' ', '_'));
                }
            }

            return builder.toString();
        }

        @Nullable
        private Annotation defineAnnotation(final Term head, final Iterable<Term> terms) {
            if (head == null) {
                return null;
            }
            Annotation ann = this.annotations.get(head.getId());
            if (ann == null) {
                ann = new Annotation(head, terms);
                this.annotations.put(head.getId(), ann);
            }
            return ann;
        }

        private IRI mintIRI(final String id, @Nullable final String suggestedLocalName) {
            String localName = this.mintedIRIs.get(id);
            if (localName == null) {
                final String name = MoreObjects.firstNonNull(suggestedLocalName, id);
                final StringBuilder builder = new StringBuilder();
                for (int i = 0; i < name.length(); ++i) {
                    final char c = name.charAt(i);
                    builder.append(Character.isWhitespace(c) ? '_' : c);
                }
                final String base = builder.toString();
                int counter = 1;
                while (true) {
                    localName = base + (counter == 1 ? "" : "_" + counter);
                    if (!this.mintedIRIs.inverse().containsKey(localName)) {
                        this.mintedIRIs.put(id, localName);
                        break;
                    }
                    ++counter;
                }
            }
            return FACTORY.createIRI(Util.cleanIRI(this.baseIRI + "#" + localName));
        }

        @Nullable
        private IRI mintRefIRI(@Nullable final String resource, @Nullable final String reference) {
            if (!Strings.isNullOrEmpty(resource) && !Strings.isNullOrEmpty(reference)) {
                final String normResource = resource.toLowerCase();
                final String namespace = RDFGenerator.this.namespaceMap.get(normResource);
                if (namespace != null) {
                    return FACTORY
                            .createIRI(Util.cleanIRI(namespace + reference.replace('#', '.')));
                }
            }
            return null;
        }

        private void emitMeta(@Nullable final IRI subject, @Nullable final IRI property,
                @Nullable final Object objects) {
            if (subject != null && property != null) {
                for (final Value object : extract(Value.class, objects,
                        RDF.TYPE.equals(property) ? RDFGenerator.this.typeMap : null)) {
                    this.statements.add(FACTORY.createStatement(subject, property, object));
                }
            }
        }

        private void emitFact(@Nullable final IRI subject, @Nullable final IRI property,
                @Nullable final Object objects, @Nullable final IRI mention,
                @Nullable final Object confidence) {
            if (subject != null && property != null) {
                for (final Value object : extract(Value.class, objects,
                        RDF.TYPE.equals(property) ? RDFGenerator.this.typeMap : null)) {
                    final IRI factIRI = hash(subject, property, object);
                    this.statements
                            .add(FACTORY.createStatement(subject, property, object, factIRI));
                    if (mention != null) {
                        this.statements.add(
                                FACTORY.createStatement(factIRI, KS_OLD.EXPRESSED_BY, mention));
                    }
                    if (confidence instanceof Number) {
                        final double confidenceValue = ((Number) confidence).doubleValue();
                        if (confidenceValue != 0.0) {
                            // this.statements.add(FACTORY.createStatement(factIRI,
                            // KS_OLD.CONFIDENCE,
                            // FACTORY.createLiteral(confidenceValue)));
                        }
                    }
                }
            }
        }

        private Iterable<Statement> merge(final Iterable<Statement> stmts)
                throws RDFHandlerException {

            final List<Statement> smushedStmts = Lists.newArrayList();

            /// ???????
            RDFProcessors.smush(null, true, "http://dbpedia.org/resource/")
                    .wrap(RDFSources.wrap(stmts)).emit(RDFHandlers.wrap(smushedStmts), 1);

            final Set<Resource> named = Sets.newHashSet();
            final Multimap<Resource, Resource> groups = HashMultimap.create();
            for (final Statement stmt : smushedStmts) {
                if (stmt.getPredicate().equals(KS_OLD.INCLUDE)) {
                    groups.put(stmt.getSubject(), (Resource) stmt.getObject());
                } else if (stmt.getPredicate().equals(FOAF.NAME)) {
                    named.add(stmt.getSubject());
                }
            }

            final List<Statement> output = Lists.newArrayList();
            final Multimap<Resource, Statement> groupProps = HashMultimap.create();
            final Multimap<Resource, Statement> groupRels = HashMultimap.create();
            for (final Statement stmt : smushedStmts) {
                final Resource subj = stmt.getSubject();
                final Value obj = stmt.getObject();
                final boolean subjIsGroup = groups.containsKey(subj);
                final boolean objIsGroup = groups.containsKey(obj);
                if (stmt.getPredicate().equals(OWL.SAMEAS)
                        && (obj instanceof BNode || obj.stringValue().startsWith(this.baseIRI))) {
                    // discard statement
                } else if (subjIsGroup && objIsGroup && !subj.equals(obj)) {
                    groupRels.put(subj, stmt);
                    groupRels.put((Resource) obj, stmt);
                } else if (subjIsGroup) {
                    groupProps.put(subj, stmt);
                } else if (objIsGroup) {
                    groupProps.put((Resource) obj, stmt);
                } else {
                    output.add(stmt);
                }
            }

            // Merge one composite / components structure at a time
            final ValueFactory vf = Statements.VALUE_FACTORY;
            for (final Resource composite : groups.keySet()) {
                final Collection<Resource> components = groups.get(composite);
                final boolean isNamed = composite instanceof IRI
                        && ((IRI) composite).getNamespace().equals("http://dbpedia.org/resource/")
                        || named.contains(composite);
                if (isNamed) {
                    output.addAll(groupProps.get(composite));
                    for (final Statement stmt : groupRels.removeAll(composite)) {
                        if (stmt.getSubject().equals(composite)) {
                            groupRels.remove(stmt.getObject(), stmt);
                            groupProps.put((Resource) stmt.getObject(), stmt);
                        } else {
                            groupRels.remove(stmt.getSubject(), stmt);
                            groupProps.put(stmt.getSubject(), stmt);
                        }
                    }
                } else {
                    for (final Statement stmt : groupRels.removeAll(composite)) {
                        final Resource subj = stmt.getSubject();
                        final IRI pred = stmt.getPredicate();
                        final Value obj = stmt.getObject();
                        final Resource ctx = stmt.getContext();
                        if (subj.equals(composite)) {
                            groupRels.remove(obj, stmt);
                            for (final Resource component : components) {
                                groupProps.put((Resource) obj,
                                        vf.createStatement(component, pred, obj, ctx));
                            }
                        } else {
                            groupRels.remove(subj, stmt);
                            for (final Resource component : components) {
                                groupProps.put(subj,
                                        vf.createStatement(subj, pred, component, ctx));
                            }
                        }
                    }
                    for (final Statement stmt : groupProps.get(composite)) {
                        final IRI pred = stmt.getPredicate();
                        final Resource ctx = stmt.getContext();
                        Collection<Resource> subjs = ImmutableList.of(stmt.getSubject());
                        Collection<? extends Value> objs = ImmutableList.of(stmt.getObject());
                        if (composite.equals(stmt.getSubject())) {
                            subjs = components;
                            if (KS_OLD.INCLUDE.equals(pred) || RDFS.LABEL.equals(pred)) {
                                continue;
                            }
                        }
                        if (composite.equals(stmt.getObject())) {
                            objs = components;
                        }
                        for (final Resource subj : subjs) {
                            for (final Value obj : objs) {
                                output.add(Statements.VALUE_FACTORY.createStatement(subj, pred,
                                        obj, ctx));
                            }
                        }
                    }
                }
            }

            return output;
        }

        @SuppressWarnings("unchecked")
        private <T extends Value> Collection<T> extract(final Class<T> clazz,
                @Nullable final Object object, @Nullable final Multimap<String, ? extends T> map) {
            if (object == null) {
                return ImmutableList.of();
            } else if (clazz.isInstance(object)) {
                return ImmutableList.of((T) object);
            } else if (object instanceof Iterable<?>) {
                final List<T> list = Lists.newArrayList();
                for (final Object element : (Iterable<?>) object) {
                    list.addAll(extract(clazz, element, map));
                }
                return list;
            } else if (object.getClass().isArray()) {
                final List<T> list = Lists.newArrayList();
                final int length = Array.getLength(object);
                for (int i = 0; i < length; ++i) {
                    list.addAll(extract(clazz, Array.get(object, i), map));
                }
                return list;
            } else if (map != null) {
                return (Collection<T>) map.get(object.toString());
            } else {
                return ImmutableList.of(Statements.convert(object, clazz));
            }
        }

        private IRI hash(final Resource subject, final IRI predicate, final Value object) {
            final List<String> list = Lists.newArrayList();
            for (final Value value : new Value[] { subject, predicate, object }) {
                if (value instanceof IRI) {
                    list.add("\u0001");
                    list.add(value.stringValue());
                } else if (value instanceof BNode) {
                    list.add("\u0002");
                    list.add(((BNode) value).getID());
                } else if (value instanceof Literal) {
                    final Literal l = (Literal) value;
                    list.add("\u0003");
                    list.add(l.getLabel());
                    if (!l.getDatatype().equals(XMLSchema.STRING)) {
                        list.add(l.getDatatype().stringValue());
                    } else if (l.getLanguage().isPresent()) {
                        list.add(l.getLanguage().get());
                    }
                }
            }
            final String id = Hash.murmur3(list.toArray(new String[list.size()])).toString();
            return FACTORY.createIRI("fact:" + id);
        }

    }

    private static final class Annotation {

        final Term head;

        final List<Term> extent;

        IRI objectIRI;

        IRI predicateIRI;

        Annotation(final Term head, final Iterable<Term> extent) {
            this.head = head;
            this.extent = ImmutableList.copyOf(extent);
            this.objectIRI = null;
            this.predicateIRI = null;
        }

    }

}
