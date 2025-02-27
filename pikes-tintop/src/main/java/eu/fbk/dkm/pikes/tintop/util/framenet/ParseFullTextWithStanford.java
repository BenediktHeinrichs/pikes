package eu.fbk.dkm.pikes.tintop.util.framenet;

import ch.qos.logback.classic.Level;
import com.google.common.io.Files;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.util.CoreMap;
import eu.fbk.dkm.pikes.depparseannotation.DepParseInfo;
import eu.fbk.dkm.pikes.depparseannotation.DepparseAnnotations;
import org.joox.JOOX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Created by alessio on 21/12/15.
 */

public class ParseFullTextWithStanford {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParseFullTextWithStanford.class);
    private static String annotatorsPattern = "tokenize, ssplit, %s, lemma, %s";

    /*
    Todo: use pos from Stanford, as POS from FrameNet is not consistent
     */

    static class SpanInformation {

        String label;
        int start, end;

        public SpanInformation(String label, int start, int end) {
            this.label = label;
            this.start = start;
            this.end = end;
        }

        @Override public String toString() {
            return "SpanInformation{" +
                    "label='" + label + '\'' +
                    ", start=" + start +
                    ", end=" + end +
                    '}';
        }
    }

    static class FrameInformation {

        SpanInformation target;
        List<SpanInformation> roles = new ArrayList<>();
        String luName, frameName;

        public FrameInformation(String luName, String frameName) {
            this.luName = luName;
            this.frameName = frameName;
        }

        public void setTarget(SpanInformation target) {
            this.target = target;
        }

        public void addRole(SpanInformation role) {
            this.roles.add(role);
        }

        @Override public String toString() {
            return "FrameInformation{" +
                    "target=" + target +
                    ", roles=" + roles +
                    ", luName='" + luName + '\'' +
                    ", frameName='" + frameName + '\'' +
                    '}';
        }
    }

    /**
     * Parse examples in FrameNet lus files and save them in Semafor format.
     * <p>
     * Arguments:
     * - FrameNet LUs folder
     * - Parsing output file (semafor.all.lemma.tags)
     * - Frames output file (semafor.frame.elements)
     * - POS (original vs. Stanford): fake_pos, pos
     *
     * @param args List of arguments (see above)
     */
    public static void main(String[] args) {

        if (args.length < 5) {
            LOGGER.error("Five arguments needed: "
                    + "fullTextPath, outputFile (parsing), outputFile (frames), posAnnotator, parseAnnotator");
        }

        String fullTextPath = args[0];
        String outputFile1 = args[1];
        String outputFile2 = args[2];
        String posAnnotator = args[3];
        String parseAnnotator = args[4];

        String annotators = String.format(annotatorsPattern, posAnnotator, parseAnnotator);

        try {

            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("edu.stanford")).setLevel(Level.ERROR);

            File fullTextFile = new File(fullTextPath);
            BufferedWriter writerLemmas = new BufferedWriter(new FileWriter(outputFile1));
            BufferedWriter writerFrames = new BufferedWriter(new FileWriter(outputFile2));

            Properties props = new Properties();
            props.setProperty("annotators", annotators);
            props.setProperty("customAnnotatorClass.fake_pos", "eu.fbk.fcw.utils.annotators.FakePosAnnotator");
            props.setProperty("customAnnotatorClass.mst_server",
                    "eu.fbk.fcw.mst.api.MstServerParserAnnotator");

            props.setProperty("tokenize.whitespace", "true");
            props.setProperty("ssplit.eolonly", "true");

            props.setProperty("mst_server.host", "localhost");
            props.setProperty("mst_server.port", "8012");

            StanfordCoreNLP pipeline = new StanfordCoreNLP(props);

            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();

            int sentNo = -1;
            for (final File file : Files.fileTreeTraverser().preOrderTraversal(fullTextFile)) {
                if (!file.isFile()) {
                    continue;
                }
                if (file.getName().startsWith(".")) {
                    continue;
                }
                if (!file.getName().endsWith(".xml")) {
                    continue;
                }

                LOGGER.info("File: {}", file.getName());

                Document doc = dBuilder.parse(file);

                for (Element sentenceElement : JOOX.$(doc).find("sentence")) {
                    String sentenceID = sentenceElement.getAttribute("ID");

                    TreeMap<Integer, String> pos = new TreeMap<>();
                    Element textElement = JOOX.$(sentenceElement).find("text").get(0);
                    String text = textElement.getTextContent();
                    LOGGER.trace(text);
                    StringBuffer stringBuffer;

                    List<FrameInformation> frames = new ArrayList<>();

                    for (Element annotationSet : JOOX.$(sentenceElement).find("annotationSet")) {
                        String luName = annotationSet.getAttribute("luName");
                        if (luName == null || luName.trim().length() == 0) {
                            for (Element layer : JOOX.$(annotationSet).find("layer")) {
                                if (layer.getAttribute("name").equals("PENN")) {
                                    for (Element label : JOOX.$(layer).find("label")) {
                                        Integer start = Integer.parseInt(label.getAttribute("start"));
                                        String thisPos = label.getAttribute("name");
                                        pos.put(start, thisPos);
                                    }
                                }
                            }
                        } else {
                            FrameInformation frameInformation = new FrameInformation(
                                    annotationSet.getAttribute("luName"), annotationSet.getAttribute("frameName"));
                            for (Element layer : JOOX.$(annotationSet).find("layer")) {
                                if (layer.getAttribute("name").equals("Target")) {
                                    for (Element label : JOOX.$(layer).find("label")) {
                                        frameInformation.setTarget(new SpanInformation(label.getAttribute("name"),
                                                        Integer.parseInt(label.getAttribute("start")),
                                                        Integer.parseInt(label.getAttribute("end"))
                                                )
                                        );
                                    }
                                }
                                if (layer.getAttribute("name").equals("FE")) {
                                    for (Element label : JOOX.$(layer).find("label")) {
                                        String start = label.getAttribute("start");
                                        String end = label.getAttribute("end");

                                        if (start.length() > 0 && end.length() > 0) {
                                            frameInformation.addRole(new SpanInformation(label.getAttribute("name"),
                                                            Integer.parseInt(start),
                                                            Integer.parseInt(end)
                                                    )
                                            );
                                        }
                                    }
                                }
                            }

                            frames.add(frameInformation);
                        }
                    }

                    stringBuffer = new StringBuffer();
                    for (Integer key : pos.keySet()) {
                        String value = pos.get(key);
                        stringBuffer.append(value).append(" ");
                    }

                    Annotation s;
                    props.setProperty("fake_pos.pos", stringBuffer.toString().trim());
                    try {
                        pipeline = new StanfordCoreNLP(props);
                        s = new Annotation(text);
                        pipeline.annotate(s);
                    } catch (Throwable e) {
                        LOGGER.warn("Skipped sentence {}:{}", file.getName(), sentenceID);
                        continue;
                    }

                    sentNo++;
                    int size = s.get(CoreAnnotations.TokensAnnotation.class).size();

                    String[] tokens = new String[size];
                    String[] poss = new String[size];
                    String[] depLabels = new String[size];
                    String[] depParents = new String[size];
                    String[] lemmas = new String[size];

                    TreeMap<Integer, Integer> ids = new TreeMap<>();
                    for (CoreMap sentence : s.get(CoreAnnotations.SentencesAnnotation.class)) {

                        DepParseInfo info;

                        if (parseAnnotator.equals("parse")) {
                            SemanticGraph dependencies = sentence.get(
                                    SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class);
                            info = new DepParseInfo(dependencies);
                        } else {
                            info = sentence.get(DepparseAnnotations.MstParserAnnotation.class);
                        }

                        for (Integer tokenID : info.getDepParents().keySet()) {
                            int parent = info.getDepParents().get(tokenID);
                            depParents[tokenID - 1] = Integer.toString(parent);
                        }
                        for (Integer tokenID : info.getDepLabels().keySet()) {
                            depLabels[tokenID - 1] = info.getDepLabels().get(tokenID);
                        }

                        java.util.List<CoreLabel> get = sentence.get(CoreAnnotations.TokensAnnotation.class);
                        for (int i = 0; i < get.size(); i++) {
                            CoreLabel token = get.get(i);
                            tokens[i] = token.get(CoreAnnotations.TextAnnotation.class);
                            poss[i] = token.get(CoreAnnotations.PartOfSpeechAnnotation.class);
                            lemmas[i] = token.get(CoreAnnotations.LemmaAnnotation.class);

                            ids.put(token.beginPosition(), i);
                        }
                    }

                    for (FrameInformation frame : frames) {
                        StringBuffer frameBuffer = new StringBuffer();

                        try {
                            int numFrameRoles = 1 + frame.roles.size();
                            frameBuffer.append(numFrameRoles);
                            frameBuffer.append("\t").append(frame.frameName);
                            frameBuffer.append("\t").append(frame.luName);

                            String interval = getInterval(frame.target, ids);
                            frameBuffer.append("\t").append(interval);

                            StringBuffer partsBuffer = new StringBuffer();
                            String[] parts = interval.split("_+");
                            for (String stringID : parts) {
                                partsBuffer.append("_").append(tokens[Integer.parseInt(stringID)]);
                            }
                            frameBuffer.append("\t").append(partsBuffer.toString().substring(1));
                            frameBuffer.append("\t").append(sentNo);
                            for (SpanInformation role : frame.roles) {
                                frameBuffer.append("\t").append(role.label);
                                frameBuffer.append("\t").append(getInterval(role, ids, true));
                            }

                            frameBuffer.append("\n");
                        } catch (Exception e) {
                            LOGGER.warn("Skipped frame: {}", frame.frameName);
                            continue;
                        }

                        writerFrames.append(frameBuffer);
                    }

                    stringBuffer = new StringBuffer();
                    stringBuffer.append(size);
                    for (String value : tokens) {
                        stringBuffer.append("\t").append(value);
                    }
                    for (String value : poss) {
                        stringBuffer.append("\t").append(value);
                    }
                    for (String value : depLabels) {
                        stringBuffer.append("\t").append(value);
                    }
                    for (String value : depParents) {
                        stringBuffer.append("\t").append(value);
                    }
                    for (int i = 0; i < tokens.length; i++) {
                        stringBuffer.append("\t").append("0");
                    }
                    for (String value : lemmas) {
                        stringBuffer.append("\t").append(value);
                    }

                    stringBuffer.append("\n");

                    writerLemmas.append(stringBuffer.toString());

                }
            }

            writerLemmas.close();
            writerFrames.close();

        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static String getInterval(
            SpanInformation span,
            TreeMap<Integer, Integer> ids) {
        return getInterval(span, ids, false);
    }

    private static String getInterval(
            SpanInformation span,
            TreeMap<Integer, Integer> ids,
            boolean forRole) {

        StringBuffer list = new StringBuffer();
        for (Integer key : ids.keySet()) {
            if (key >= span.start && key <= span.end) {
                list.append("_").append(ids.get(key));
            }
        }

        if (list.toString().length() == 0) {
            return "";
        }

        if (!forRole) {
            return list.toString().substring(1);
        }

        String[] parts = list.toString().substring(1).split("_+");
        if (parts.length == 1) {
            return parts[0];
        }

        return parts[0] + ":" + parts[parts.length - 1];
    }
}
