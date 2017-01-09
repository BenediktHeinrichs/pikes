package eu.fbk.dkm.pikes.tintop;

import edu.stanford.nlp.hcoref.CorefCoreAnnotations;
import edu.stanford.nlp.hcoref.data.CorefChain;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.time.TimeAnnotations;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.IntPair;
import ixa.kaflib.KAFDocument;
import ixa.kaflib.Span;
import ixa.kaflib.Term;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Created by alessio on 26/02/15.
 */

public class StanfordTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(StanfordTest.class);

    private static void printOutput(Annotation annotation) {
        List<CoreMap> sents = annotation.get(CoreAnnotations.SentencesAnnotation.class);
        for (CoreMap thisSent : sents) {

            List<CoreLabel> tokens = thisSent.get(CoreAnnotations.TokensAnnotation.class);
            for (CoreLabel token : tokens) {
                System.out.println("Token: " + token);
                System.out.println("Index: " + token.index());
                System.out.println("Sent index: " + token.sentIndex());
                System.out.println("Begin: " + token.get(CoreAnnotations.CharacterOffsetBeginAnnotation.class));
                System.out.println("End: " + token.get(CoreAnnotations.CharacterOffsetEndAnnotation.class));
                System.out.println("NER: " + token.get(CoreAnnotations.NamedEntityTagAnnotation.class));
                System.out.println(token.get(CoreAnnotations.NamedEntityTagAnnotation.class));
                System.out.println(token.get(CoreAnnotations.NormalizedNamedEntityTagAnnotation.class));
                System.out.println(token.get(CoreAnnotations.ValueAnnotation.class));
//                System.out.println(token.get(TimeExpression.Annotation.class));
//                System.out.println(token.get(TimeExpression.TimeIndexAnnotation.class));
//                System.out.println(token.get(CoreAnnotations.DistSimAnnotation.class));
//                System.out.println(token.get(CoreAnnotations.NumericCompositeTypeAnnotation.class));
                System.out.println(token.get(TimeAnnotations.TimexAnnotation.class));
//                System.out.println(token.get(CoreAnnotations.NumericValueAnnotation.class));
//                System.out.println(token.get(TimeExpression.ChildrenAnnotation.class));
//                System.out.println(token.get(CoreAnnotations.NumericTypeAnnotation.class));
//                System.out.println(token.get(CoreAnnotations.ShapeAnnotation.class));
//                System.out.println(token.get(Tags.TagsAnnotation.class));
//                System.out.println(token.get(CoreAnnotations.NumerizedTokensAnnotation.class));
//                System.out.println(token.get(CoreAnnotations.AnswerAnnotation.class));
//                System.out.println(token.get(CoreAnnotations.NumericCompositeValueAnnotation.class));

                System.out.println();
            }

            System.out.println("---");
            System.out.println();
        }
    }

    public static void main(String[] args) throws IOException {

        String text = "Donald Trump set off a fierce new controversy Tuesday with remarks about the right to bear arms that were interpreted by many as a threat of violence against Hillary Clinton.";
        text = "G. W. Bush and Bono are very strong supporters of the fight of HIV in Africa. Their March 2002 meeting resulted in a 5 billion dollar aid.";

        Properties props;
        Annotation annotation;

        props = new Properties();
        props.setProperty("annotators", "tokenize, ssplit, pos, lemma, ner, parse, dcoref");

        StanfordCoreNLP pipeline = new StanfordCoreNLP(props);
        annotation = new Annotation(text);
//        annotation.set(CoreAnnotations.DocDateAnnotation.class, "2016-08-10T13:51:41+02:00");
        pipeline.annotate(annotation);

        Map<Integer, CorefChain> coreferenceGraph = annotation.get(CorefCoreAnnotations.CorefChainAnnotation.class);
        for (Object c : coreferenceGraph.keySet()) {
            CorefChain chain = coreferenceGraph.get(c);
            Map<IntPair, Set<CorefChain.CorefMention>> mentionMap = chain.getMentionMap();

            System.out.println(mentionMap);
            for (IntPair p : mentionMap.keySet()) {
                for (CorefChain.CorefMention m : mentionMap.get(p)) {
                    System.out.println(m.sentNum);
                    System.out.println(m.startIndex);
                    System.out.println(m.endIndex);
                }
            }
        }

//        if (text.length() < 1000) {
//            printOutput(annotation);
//        }

    }
}
