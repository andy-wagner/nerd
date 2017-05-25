package com.scienceminer.nerd.disambiguation;

import java.util.*;
import java.io.*;
import java.util.regex.*;

import com.scienceminer.nerd.kb.*;
import com.scienceminer.nerd.disambiguation.NerdCandidate;
import com.scienceminer.nerd.utilities.NerdProperties;
import com.scienceminer.nerd.utilities.NerdConfig;
import com.scienceminer.nerd.exceptions.*;

import org.grobid.core.utilities.OffsetPosition;
import org.grobid.core.data.Entity;
import org.grobid.core.lang.Language;
import org.grobid.core.utilities.LanguageUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import com.scienceminer.nerd.kb.model.*;
import org.wikipedia.miner.annotation.*;
import org.wikipedia.miner.util.*;
import org.wikipedia.miner.util.text.*;

import com.scienceminer.nerd.kb.model.Label.Sense;
import com.scienceminer.nerd.kb.db.KBDatabase.DatabaseType;
import com.scienceminer.nerd.features.*;

import smile.validation.ConfusionMatrix;
import smile.validation.FMeasure;
import smile.validation.Precision;
import smile.validation.Recall;
import smile.data.*;
import smile.data.parser.*;
import smile.regression.*;
import com.thoughtworks.xstream.*;

/**
 * A machine learning model for ranking a list of ambiguous candidates for a given mention.
 */
public class NerdRanker {
	/**
	 * The class Logger.
	 */
	private static final Logger logger = LoggerFactory.getLogger(NerdRanker.class);

	// ranker model files
	private static String MODEL_PATH_LONG = "data/models/ranker-long";

	private Wikipedia wikipedia = null;
	private ArticleCleaner cleaner = null;

	private double minSenseProbability = 0.0; 
	private int maxLabelLength = 20; 
	private double minLinkProbability = 0.0;
	private int maxContextSize = -1;

	// regression model
	private RandomForest forest = null;

	// for serialization of the classifier
	private XStream xstream = null;
	private ArffParser arffParser = null;

	private String arffDataset = null;
	private AttributeDataset attributeDataset = null;

	public NerdRanker(Wikipedia wikipedia) throws Exception {
		this.wikipedia = wikipedia;
		this.minSenseProbability = NerdEngine.minSenseProbability;
		this.maxLabelLength = NerdEngine.maxLabelLength;
		this.minLinkProbability = NerdEngine.minLinkProbability;
		this.maxContextSize = NerdEngine.maxContextSize;
		
		NerdConfig conf = wikipedia.getConfig();
		cleaner = new ArticleCleaner();
		
		xstream = new XStream();
		arffParser = new ArffParser();
		GenericRankerFeatureVector feature = new MilneWittenFeatureVector();
		arffParser.setResponseIndex(feature.getNumFeatures()-1);
	}

	public NerdRanker(Wikipedia wikipedia, 
						double minSenseProbability,
						int maxLabelLength, 
						double minLinkProbability,
						int maxContextSize) throws Exception {
		this.wikipedia = wikipedia;
		this.minSenseProbability = minSenseProbability;
		this.maxLabelLength = maxLabelLength;
		this.minLinkProbability = minLinkProbability;
		this.maxContextSize = maxContextSize;
		
		NerdConfig conf = wikipedia.getConfig();
		cleaner = new ArticleCleaner();
		
		xstream = new XStream();
		arffParser = new ArffParser();
		GenericRankerFeatureVector feature = new MilneWittenFeatureVector();
		arffParser.setResponseIndex(feature.getNumFeatures()-1);
	}

	@SuppressWarnings("unchecked")
	private void weightTrainingInstances() {
		double positiveInstances = 0;
		double negativeInstances = 0; 

		/*attributeDataset 

		Enumeration<Instance> e = dataset.enumerateInstances();
		while (e.hasMoreElements()) {
			Instance i = (Instance) e.nextElement();
			double isValidSense = i.value(3);
			if (isValidSense == 0) 
				positiveInstances ++;
			else
				negativeInstances ++;
		}

		double p = (double) positiveInstances / (positiveInstances + negativeInstances);
		e = dataset.enumerateInstances();
		while (e.hasMoreElements()) {
			Instance i = (Instance) e.nextElement();
			double isValidSense = i.value(3);
			if (isValidSense == 0) 
				i.setWeight(0.5 * (1.0/p));
			else
				i.setWeight(0.5 * (1.0/(1-p)));
		}*/
	}

	public double getProbability(double commonness, double relatedness, double quality) throws Exception {
		if (forest == null) {
			// load model
			File modelFile = new File(MODEL_PATH_LONG+"-"+wikipedia.getConfig().getLangCode()+".model"); 
			if (!modelFile.exists()) {
                logger.debug("Invalid model file for nerd ranker.");
			}
			String xml = FileUtils.readFileToString(modelFile, "UTF-8");
			forest = (RandomForest)xstream.fromXML(xml);
			logger.info("Model for nerd ranker loaded: " + 
				MODEL_PATH_LONG+"-"+wikipedia.getConfig().getLangCode()+".model");
		}

		GenericRankerFeatureVector feature = new MilneWittenFeatureVector();
		//GenericRankerFeatureVector feature = new SimpleNerdFeatureVector();
		feature.prob_c = commonness;
		feature.relatedness = relatedness;
		feature.context_quality = quality;
		//feature.dice_coef = dice_coef;
		double[] features = feature.toVector();
		return forest.predict(features);
	}

	public void saveTrainingData(File file) throws IOException, Exception {
		FileUtils.writeStringToFile(file, arffDataset);
		System.out.println("Training data saved under " + file.getPath());
	}
	
	public void loadTrainingData(File file) throws Exception{
		attributeDataset = arffParser.parse(new FileInputStream(file));
		System.out.println("Training data loaded from file " + file.getPath());
		weightTrainingInstances();
	}
	
	public void clearTrainingData() {
		//dataset = null;
		arffDataset = null;
		attributeDataset = null;
	}

	public void saveModel(File file) throws IOException, Exception {
		logger.info("saving model");
		// save the model with XStream
		String xml = xstream.toXML(forest);
		File modelFile = new File(MODEL_PATH_LONG+"-"+wikipedia.getConfig().getLangCode()+".model"); 
		if (!modelFile.exists()) {
            logger.debug("Invalid file for saving author filtering model.");
		}
		FileUtils.writeStringToFile(modelFile, xml, "UTF-8");
		System.out.println("Model saved under " + file.getPath());
	}

	public void loadModel(File file) throws IOException, Exception {
		logger.info("loading model");
		// load model
		File modelFile = new File(MODEL_PATH_LONG+"-"+wikipedia.getConfig().getLangCode()+".model"); 
		if (!modelFile.exists()) {
        	logger.debug("Model file for nerd ranker does not exist.");
        	throw new NerdResourceException("Model file for nerd ranker does not exist.");
		}
		String xml = FileUtils.readFileToString(modelFile, "UTF-8");
		forest = (RandomForest)xstream.fromXML(xml);
		logger.debug("Model for nerd ranker loaded.");
	}

	public void trainModel() throws Exception {
		if (attributeDataset == null) {
			logger.debug("Training data for nerd ranker has not been loaded or prepared");
			throw new NerdResourceException("Training data for nerd ranker has not been loaded or prepared");
		}
		logger.info("building model");
		//decider.train(classifier, dataset);
		double[][] x = attributeDataset.toArray(new double[attributeDataset.size()][]);
		double[] y = attributeDataset.toArray(new double[attributeDataset.size()]);
		
		long start = System.currentTimeMillis();
		forest = new RandomForest(attributeDataset.attributes(), x, y, 200);
		
        System.out.println("NERD ranker model created in " + 
			(System.currentTimeMillis() - start) / (1000.00) + " seconds");
	}

	public void train(ArticleSet articles, String datasetName) throws Exception {
		StringBuilder arffBuilder = new StringBuilder();
		GenericRankerFeatureVector feat = new MilneWittenFeatureVector();
		arffBuilder.append(feat.getArffHeader()).append("\n");
		int nbArticle = 0;
		for (Article art: articles) {
			arffBuilder = trainArticle(art, arffBuilder);	
			nbArticle++;
System.out.println("nb article processed: " + nbArticle);
		}
		System.out.println(arffBuilder.toString());
		arffDataset = arffBuilder.toString();
		attributeDataset = arffParser.parse(IOUtils.toInputStream(arffDataset, "UTF-8"));
		weightTrainingInstances();
	}

	private StringBuilder trainArticle(Article article, StringBuilder arffBuilder) throws Exception {
		List<Label.Sense> unambigLabels = new ArrayList<Label.Sense>();
		Vector<TopicReference> ambigRefs = new Vector<TopicReference>();

		String content = cleaner.getMarkupLinksOnly(article);
		content = content.replace("''", "");
		StringBuilder contentText = new StringBuilder(); 
System.out.println(content);
		Pattern linkPattern = Pattern.compile("\\[\\[(.*?)\\]\\]"); 
		Matcher linkMatcher = linkPattern.matcher(content);

		// split references into ambiguous and unambiguous
		int head = 0;
		while (linkMatcher.find()) {			
			String linkText = content.substring(linkMatcher.start()+2, linkMatcher.end()-2);
			if (head != linkMatcher.start())
				contentText.append(content.substring(head, linkMatcher.start()));

			String labelText = linkText;
			String destText = linkText;

			int pos = linkText.lastIndexOf('|');
			if (pos>0) {
				destText = linkText.substring(0, pos);
				labelText = linkText.substring(pos+1);
			} else {
				// labelText and destText are the same, but we could have an anchor #
				int pos2 = linkText.indexOf('#');
				if (pos2 != -1) {
					destText = linkText.substring(0,pos2);
				} else {
					destText = linkText;
				}
				labelText = destText;
			}
			contentText.append(labelText);

			head = linkMatcher.end();
			
			Label label = new Label(wikipedia.getEnvironment(), labelText);
			Label.Sense[] senses = label.getSenses();
			Article dest = wikipedia.getArticleByTitle(destText);
			
			if (dest != null && senses.length >= 1) {
				TopicReference ref = new TopicReference(label, 
						dest.getId(), 
						new OffsetPosition(contentText.length()-labelText.length(), contentText.length()));

				if (senses.length == 1 || senses[0].getPriorProbability() >= (1-minSenseProbability))
					unambigLabels.add(senses[0]);
				else {
					ambigRefs.add(ref);
System.out.println(linkText + ", " + labelText + ", " + 
	destText + " / " + ref.getOffsetStart() + " " + ref.getOffsetEnd());
				}
			}
		}
		contentText.append(content.substring(head));
		String contentString = contentText.toString();
System.out.println("Cleaned content: " + contentString);
		
		// get candidates for this content
		NerdEngine nerdEngine = NerdEngine.getInstance();
		Relatedness relatedness = Relatedness.getInstance();

		// process the text
		ProcessText processText = ProcessText.getInstance();
		List<Entity> entities = new ArrayList<Entity>();
		String lang = wikipedia.getConfig().getLangCode();
		if (lang.equals("en") || lang.equals("fr")) {
			entities = processText.process(contentString, new Language(lang, 1.0));
		}
System.out.println("number of NE found: " + entities.size());		
		List<Entity> entities2 = processText.processBrutal(contentString, lang);
System.out.println("number of non-N entities found: " + entities2.size());	
		for(Entity entity : entities2) {
			// we add entities only if the mention is not already present
			if (!entities.contains(entity))
				entities.add(entity);
		}

		if (entities == null) 
			return arffBuilder;

		// disambiguate and solve entity mentions
		List<NerdEntity> disambiguatedEntities = new ArrayList<NerdEntity>();
		for (Entity entity : entities) {
			NerdEntity nerdEntity = new NerdEntity(entity);
			disambiguatedEntities.add(nerdEntity);
		}
System.out.println("total entities to disambiguate: " + disambiguatedEntities.size());	

		Map<NerdEntity, List<NerdCandidate>> candidates = 
			nerdEngine.generateCandidates(disambiguatedEntities, lang);
System.out.println("total entities with candidates: " + candidates.size());
		// set the expected concept to the NerdEntity
		for (Map.Entry<NerdEntity, List<NerdCandidate>> entry : candidates.entrySet()) {
			List<NerdCandidate> cands = entry.getValue();
			NerdEntity entity = entry.getKey();

			/*for (NerdCandidate cand : cands) {
				System.out.println(cand.toString());
			}*/

			int start = entity.getOffsetStart();
			int end = entity.getOffsetEnd();
//System.out.println("entity: " + start + " / " + end + " - " + contentString.substring(start, end));
			for(TopicReference ref : ambigRefs) {
				int start_ref = ref.getOffsetStart();
				int end_ref = ref.getOffsetEnd();
				if ( (start_ref == start) && (end_ref == end) ) {
					entity.setWikipediaExternalRef(ref.getTopicId());
					break;
				} 
			}
		}

		// get context for this content
System.out.println("get context for this content");		
		NerdContext context = null;
		try {
			 context = relatedness.getContext(candidates, null, lang);
		} catch(Exception e) {
			e.printStackTrace();
		}

		double quality = (double)context.getQuality();
		int nbInstance = 0;
		// second pass for producing the disambiguation observations
		for (Map.Entry<NerdEntity, List<NerdCandidate>> entry : candidates.entrySet()) {
			List<NerdCandidate> cands = entry.getValue();
			NerdEntity entity = entry.getKey();
			int expectedId = entity.getWikipediaExternalRef();
			int nbCandidate = 0;
			if (expectedId == -1)
				continue;
			if ((cands == null) || (cands.size() <= 1)) {
				// do not considerer unambiguous entities
				continue;
			}
			
			for(NerdCandidate candidate : cands) {
				try {
					nbCandidate++;
					System.out.println(nbCandidate + " candidate / " + cands.size());
					Label.Sense sense = candidate.getWikiSense();
					if (sense == null)
						continue;

					double commonness = sense.getPriorProbability();
					System.out.println("commonness: " + commonness);

					double related = relatedness.getRelatednessTo(candidate, context, lang);
					System.out.println("relatedness: " + related);

					GenericRankerFeatureVector feature = new MilneWittenFeatureVector();
					feature.prob_c = commonness;
					feature.relatedness = related;
					feature.context_quality = quality;
					feature.label = (expectedId == candidate.getWikipediaExternalRef()) ? 1.0 : 0.0;

					arffBuilder.append(feature.printVector()).append("\n");
					nbInstance++;
					
					System.out.println("*"+candidate.getWikiSense().getTitle() + "* " + 
							entity.toString());
					System.out.println("\t\t" + "commonness: " + commonness + 
						", relatedness: " + related + 
						", quality: " + quality);
				}
				catch(Exception e) {
					e.printStackTrace();
				}
							}
			Collections.sort(cands);
		}

		System.out.println("Final Article: " + nbInstance + " training instances");
		return arffBuilder;
	}

	public Result<Integer> test(ArticleSet testSet) throws Exception{
		Result<Integer> r = new Result<Integer>();
		
		double worstRecall = 1;
		double worstPrecision = 1;
		
		int articlesTested = 0;
		int perfectRecall = 0;
		int perfectPrecision = 0;
		
		for (Article art : testSet) {			
			articlesTested ++;
			
			Result<Integer> ir = testArticle(art);
			
			if (ir.getRecall() ==1) perfectRecall++;
			if (ir.getPrecision() == 1) perfectPrecision++;
			
			worstRecall = Math.min(worstRecall, ir.getRecall());
			worstPrecision = Math.min(worstPrecision, ir.getPrecision());
			
			r.addIntermediateResult(ir);
			System.out.println("articlesTested: " + articlesTested);
		}

		System.out.println("worstR:" + worstRecall + ", worstP:" + worstPrecision);
		System.out.println("tested:" + articlesTested + ", perfectR:" + perfectRecall + ", perfectP:" + perfectPrecision);
		
		return r;
	}

	private Result<Integer> testArticle(Article article) throws Exception {
		System.out.println(" - testing " + article);

		List<Label.Sense> unambigLabels = new ArrayList<Label.Sense>();
		Vector<TopicReference> ambigRefs = new Vector<TopicReference>();

		String content = cleaner.getMarkupLinksOnly(article);

		Pattern linkPattern = Pattern.compile("\\[\\[(.*?)\\]\\]"); 
		Matcher linkMatcher = linkPattern.matcher(content);

		Set<Integer> goldStandard = new HashSet<Integer>();
		Set<Integer> disambiguatedLinks = new HashSet<Integer>();

		while (linkMatcher.find()) {			
			String linkText = content.substring(linkMatcher.start()+2, linkMatcher.end()-2);

			String labelText = linkText;
			String destText = linkText;

			int pos = linkText.lastIndexOf('|');
			if (pos>0) {
				destText = linkText.substring(0, pos);
				labelText = linkText.substring(pos+1);
			}

			destText = Character.toUpperCase(destText.charAt(0)) + destText.substring(1);     // Get first char and capitalize

			Label label = new Label(wikipedia.getEnvironment(), labelText);
			Label.Sense[] senses = label.getSenses();
			Article dest = wikipedia.getArticleByTitle(destText);

			if (senses.length > 0 && dest != null) {

				goldStandard.add(dest.getId());

				if (senses.length == 1 || senses[0].getPriorProbability() >= (1-minSenseProbability)) { 
					unambigLabels.add(senses[0]);
					disambiguatedLinks.add(dest.getId());
				} else {
					TopicReference ref = new TopicReference(label, dest.getId(), null);
					ambigRefs.add(ref);
				}
			}
		}

		// use all terms as context
		Relatedness relatedness = Relatedness.getInstance();
		//NerdContext context = relatedness.getContext(article, snippetLength);
		
		//only use links
		NerdContext context = new NerdContext(unambigLabels, null, wikipedia.getConfig().getLangCode());
		//NerdContext context = relatedness.getContext(unambigLabels, snippetLength);
		double quality =  context.getQuality();
		// resolve senses		
		for (TopicReference ref: ambigRefs) {

			TreeSet<Article> validSenses = new TreeSet<Article>();

			for (Sense sense:ref.getLabel().getSenses()) {

				if (sense.getPriorProbability() < minSenseProbability) break;

				double prob = getProbability(sense.getPriorProbability(), context.getRelatednessTo(sense), quality); 

				if (prob>0.5) {
					Article art = new Article(wikipedia.getEnvironment(), sense.getId());
					art.setWeight(prob);
					validSenses.add(art);					
				}
			}

			//use most valid sense
			if (!validSenses.isEmpty()) 
				disambiguatedLinks.add(validSenses.first().getId());
		}

		Result<Integer> result = new Result<Integer>(disambiguatedLinks, goldStandard);

		System.out.println("   " + result);

		return result;
	}

}