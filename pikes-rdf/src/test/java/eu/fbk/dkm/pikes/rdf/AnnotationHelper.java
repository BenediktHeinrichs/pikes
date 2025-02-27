package eu.fbk.dkm.pikes.rdf;

import java.io.File;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandler;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ixa.kaflib.Coref;
import ixa.kaflib.Entity;
import ixa.kaflib.ExternalRef;
import ixa.kaflib.KAFDocument;
import ixa.kaflib.LinkedEntity;
import ixa.kaflib.Predicate;
import ixa.kaflib.Predicate.Role;
import ixa.kaflib.Span;
import ixa.kaflib.Term;
import ixa.kaflib.WF;

import eu.fbk.dkm.pikes.resources.NAFUtils;
import eu.fbk.rdfpro.util.IO;
import eu.fbk.rdfpro.util.Statements;

public class AnnotationHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnnotationHelper.class);

    private static final String NS_NB = "http://pikes.fbk.eu/ontologies/nombank#";

    private static final String NS_PB = "http://pikes.fbk.eu/ontologies/propbank#";

    private static final String NS_VN = "http://pikes.fbk.eu/ontologies/verbnet#";

    private static final String NS_FN = "http://pikes.fbk.eu/ontologies/framenet#";

    private static final String PARTICIPATION_REGEX = ""
            + "SUB? (COORD CONJ?)* (PMOD (COORD CONJ?)*)? ((VC OPRD?)|(IM OPRD?))*";

    public static void main(final String... args) {

        for (final String arg : args) {

            LOGGER.info("Processing {} ...", arg);

            final String lcaseName = arg.toLowerCase();
            int index = lcaseName.lastIndexOf(".naf");
            if (index < 0) {
                index = lcaseName.lastIndexOf(".xml");
                if (index < 0) {
                    index = lcaseName.length();
                }
            }
            final String prefix = arg.substring(0, index);

            try (Reader reader = IO.utf8Reader(IO.read(arg))) {
                final KAFDocument document = KAFDocument.createFromStream(reader);
                for (int i = 1; i <= document.getNumSentences(); ++i) {
                    final Writer writer = new StringWriter();
                    final RDFWriter rdfWriter = Rio.createWriter(RDFFormat.TURTLE, writer);
                    process(document, i, rdfWriter);
                    String rdf = writer.toString();
                    rdf = rdf.replace("\n\n", "\n");
                    rdf = rdf.replace("# \n", "\n");
                    Files.write(rdf, new File(prefix + "." + i + ".ttl"), Charsets.UTF_8);
                }
            } catch (final Throwable ex) {
                LOGGER.error("Failed to process " + arg, ex);
            }
        }
        LOGGER.info("Done");
    }

    public static void process(final KAFDocument document, final int sentence,
            final RDFHandler handler) throws RDFHandlerException {

        final List<Term> terms = document.getSentenceTerms(sentence);
        final String text = getText(terms);

        final String ns = document.getPublic().uri + "." + sentence + "#";
        final Map<Term, IRI> termIRIs = getIRIs(terms, ns);

        handler.startRDF();
        handler.handleNamespace("rdfs", RDFS.NAMESPACE);
        handler.handleNamespace("owl", OWL.NAMESPACE);
        handler.handleNamespace("dbpedia", "http://dbpedia.org/resource/");
        handler.handleNamespace("pb", NS_PB);
        handler.handleNamespace("nb", NS_NB);
        handler.handleNamespace("vn", NS_VN);
        handler.handleNamespace("fn", NS_FN);
        handler.handleNamespace("", ns);

        handler.handleComment("");
        handler.handleComment("");
        handler.handleComment("=== TEXT ===");
        handler.handleComment("");

        handler.handleStatement(Statements.VALUE_FACTORY.createStatement(Statements.VALUE_FACTORY.createIRI(ns), RDFS.LABEL, Statements.VALUE_FACTORY.createLiteral(
                "\n\t" + text)));

        handler.handleComment("");
        handler.handleComment("");
        handler.handleComment("=== COREFERENCE ===");

        for (final Coref coref : document.getCorefs()) {
            final List<Span<Term>> spans = Lists.newArrayList();
            for (final Span<Term> span : coref.getSpans()) {
                if (span.getFirstTarget().getSent() == sentence) {
                    spans.add(span);
                }
            }
            if (spans.size() > 1) {
                final StringBuilder builder = new StringBuilder();
                int index = 0;
                for (final Span<Term> span : spans) {
                    builder.append(index == 0 ? "" : "  ").append("span").append(++index)
                            .append("='").append(getText(span.getTargets())).append("'");
                }
                handler.handleComment("");
                handler.handleComment(builder.toString());
                final List<IRI> headIRIs = Lists.newArrayList();
                for (final Span<Term> span : spans) {
                    final Term head = NAFUtils.extractHead(document, span);
                    headIRIs.add(termIRIs.get(head));
                }
                Collections.sort(headIRIs, Statements.valueComparator());
                for (int i = 1; i < headIRIs.size(); ++i) {
                    handler.handleStatement(Statements.VALUE_FACTORY.createStatement(headIRIs.get(0), OWL.SAMEAS,
                            headIRIs.get(i)));
                }
            }
        }

        handler.handleComment("");
        handler.handleComment("");
        handler.handleComment("=== LINKING ===");
        handler.handleComment("");

        final Multimap<IRI, IRI> links = HashMultimap.create();
        for (final LinkedEntity entity : document.getLinkedEntities()) {
            if (entity.getWFs().getFirstTarget().getSent() == sentence) {
                final Span<Term> span = KAFDocument.newTermSpan(document.getTermsByWFs(entity
                        .getWFs().getTargets()));
                final Term head = NAFUtils.extractHead(document, span);
                links.put(termIRIs.get(head), Statements.VALUE_FACTORY.createIRI(entity.getReference()));
            }
        }
        for (final Entity entity : document.getEntities()) {
            if (entity.getSpans().get(0).getFirstTarget().getSent() == sentence) {
                final Term head = NAFUtils.extractHead(document, entity.getSpans().get(0));
                for (final ExternalRef ref : entity.getExternalRefs()) {
                    if (ref.getResource().toLowerCase().contains("spotlight")) {
                        links.put(termIRIs.get(head), Statements.VALUE_FACTORY.createIRI(ref.getReference()));
                    }
                }
            }
        }
        for (final IRI termIRI : Ordering.from(Statements.valueComparator()).sortedCopy(
                links.keySet())) {
            for (final IRI linkIRI : Ordering.from(Statements.valueComparator()).sortedCopy(
                    links.get(termIRI))) {
                handler.handleStatement(Statements.VALUE_FACTORY.createStatement(termIRI, OWL.SAMEAS, linkIRI));
            }
        }

        handler.handleComment("");
        handler.handleComment("");
        handler.handleComment("=== FRAMES ===");

        for (final Predicate pred : document.getPredicatesBySent(sentence)) {

            final Term predTerm = NAFUtils.extractHead(document, pred.getSpan());
            final IRI predIRI = termIRIs.get(predTerm);

            final StringBuilder builder = new StringBuilder();
            builder.append("pred='").append(predTerm.getStr()).append("'");
            for (final Role role : pred.getRoles()) {
                builder.append("  ").append(role.getSemRole()).append("='")
                        .append(getText(role.getTerms())).append("'");
            }
            handler.handleComment("");
            handler.handleComment(builder.toString());

            final List<IRI> typeIRIs = Lists.newArrayList();
            for (final ExternalRef ref : pred.getExternalRefs()) {
                if (Strings.isNullOrEmpty(ref.getReference())) {
                    continue;
                } else if (NAFUtils.RESOURCE_PROPBANK.equals(ref.getResource())) {
                    typeIRIs.add(Statements.VALUE_FACTORY.createIRI(NS_PB + ref.getReference()));
                } else if (NAFUtils.RESOURCE_NOMBANK.equals(ref.getResource())) {
                    typeIRIs.add(Statements.VALUE_FACTORY.createIRI(NS_NB + ref.getReference()));
                } else if (NAFUtils.RESOURCE_VERBNET.equals(ref.getResource())) {
                    typeIRIs.add(Statements.VALUE_FACTORY.createIRI(NS_VN + ref.getReference()));
                } else if (NAFUtils.RESOURCE_FRAMENET.equals(ref.getResource())) {
                    typeIRIs.add(Statements.VALUE_FACTORY.createIRI(NS_FN + ref.getReference()));
                }
            }
            Collections.sort(typeIRIs, Statements.valueComparator());
            for (final IRI typeIRI : typeIRIs) {
                handler.handleStatement(Statements.VALUE_FACTORY.createStatement(predIRI, RDF.TYPE, typeIRI));
            }

            for (final Role role : pred.getRoles()) {

                final Set<IRI> roleIRIs = Sets.newHashSet();
                roleIRIs.add(Statements.VALUE_FACTORY.createIRI((predTerm.getMorphofeat().startsWith("VB") ? NS_PB
                        : NS_NB) + role.getSemRole().toLowerCase()));
                for (final ExternalRef ref : role.getExternalRefs()) {
                    String id = ref.getReference().toLowerCase();
                    final int index = id.lastIndexOf('@');
                    id = index < 0 ? id : id.substring(index + 1);
                    if (Strings.isNullOrEmpty(id)) {
                        continue;
                    } else if (NAFUtils.RESOURCE_VERBNET.equals(ref.getResource())) {
                        roleIRIs.add(Statements.VALUE_FACTORY.createIRI(NS_VN + id));
                    } else if (NAFUtils.RESOURCE_FRAMENET.equals(ref.getResource())) {
                        roleIRIs.add(Statements.VALUE_FACTORY.createIRI(NS_FN + id));
                    }
                }

                final List<IRI> argIRIs = Lists.newArrayList();
                final Term roleHead = NAFUtils.extractHead(document, role.getSpan());
                if (roleHead != null) {
                    final Set<Term> argHeads = Sets.newHashSet();
                    for (final Term term : document.getTermsByDepAncestors(
                            Collections.singleton(roleHead), PARTICIPATION_REGEX)) {
                        final String pos = term.getMorphofeat();
                        if (pos.startsWith("NN") || pos.startsWith("VB") || pos.startsWith("JJ")
                                || pos.startsWith("RB") || pos.startsWith("PRP") || pos.startsWith("WP")) {
                            argHeads.add(NAFUtils.syntacticToSRLHead(document, term));
                        }
                    }
                    for (final Term argHead : argHeads) {
                        argIRIs.add(termIRIs.get(argHead));
                    }
                    Collections.sort(argIRIs, Statements.valueComparator());
                }

                for (final IRI roleIRI : Ordering.from(Statements.valueComparator()).sortedCopy(
                        roleIRIs)) {
                    for (final IRI argIRI : argIRIs) {
                        handler.handleStatement(Statements.VALUE_FACTORY.createStatement(predIRI, roleIRI, argIRI));
                    }
                }
            }
        }

        handler.endRDF();
    }

    private static Map<Term, IRI> getIRIs(final List<Term> terms, final String ns) {

        final List<String> termStrings = Lists.newArrayList();
        for (final Term term : terms) {
            String s = term.getStr().toLowerCase();
            for (int i = 0; i < s.length(); ++i) {
                final char c = s.charAt(i);
                if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9')
                        && c != '-' && c != '_') {
                    s = s.substring(0, i);
                    break;
                }
            }
            termStrings.add(s);
        }

        final Map<Term, IRI> uris = Maps.newHashMap();
        for (int i = 0; i < terms.size(); ++i) {
            final String is = termStrings.get(i);
            int index = 0;
            int count = 0;
            for (int j = 0; j < terms.size(); ++j) {
                if (j == i) {
                    index = count;
                }
                if (termStrings.get(j).equals(is)) {
                    ++count;
                }
            }
            final String id = count <= 1 ? is : is + "_" + (index + 1);
            uris.put(terms.get(i), Statements.VALUE_FACTORY.createIRI(ns + id));
        }
        return uris;
    }

    private static String getText(final List<Term> terms) {
        final StringBuilder builder = new StringBuilder();
        boolean atBeginning = true;
        for (final Term term : terms) {
            for (final WF word : term.getWFs()) {
                final String s = word.getForm();
                final boolean punct = ",".equals(s) || ";".equals(s) || ".".equals(s)
                        || ":".equals(s);
                builder.append(atBeginning || punct ? "" : " ");
                builder.append(word.getForm());
                atBeginning = false;
            }
        }
        return builder.toString();
    }

}
