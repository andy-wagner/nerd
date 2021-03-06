package com.scienceminer.nerd.disambiguation;

import java.util.*;
import java.io.*;
import java.util.regex.*;
import java.text.*;

import com.scienceminer.nerd.kb.*;
import com.scienceminer.nerd.disambiguation.NerdCandidate;
import com.scienceminer.nerd.utilities.NerdProperties;
import com.scienceminer.nerd.utilities.NerdConfig;
import com.scienceminer.nerd.exceptions.*;
import com.scienceminer.nerd.evaluation.*;

import org.grobid.core.utilities.OffsetPosition;
import org.grobid.core.data.Entity;
import org.grobid.core.lang.Language;
import org.grobid.core.utilities.LanguageUtilities;
import org.grobid.trainer.LabelStat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import com.scienceminer.nerd.kb.model.*;
import com.scienceminer.nerd.kb.LowerKnowledgeBase;
import com.scienceminer.nerd.training.*;
import com.scienceminer.nerd.utilities.mediaWiki.MediaWikiParser;
import com.scienceminer.nerd.evaluation.*;

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
public class NerdRanker extends NerdModel {
	/**
	 * The class Logger.
	 */
	private static final Logger logger = LoggerFactory.getLogger(NerdRanker.class);

	// ranker model files
	private static String MODEL_PATH_LONG = "data/models/ranker-long";

	private LowerKnowledgeBase wikipedia = null;

	public NerdRanker(LowerKnowledgeBase wikipedia) throws Exception {
		this.wikipedia = wikipedia;

		//model = MLModel.GRADIENT_TREE_BOOST;
		model = MLModel.RANDOM_FOREST;

		GenericRankerFeatureVector feature = new SimpleRankerFeatureVector();
		arffParser.setResponseIndex(feature.getNumFeatures()-1);
	}

	public double getProbability(double commonness, double relatedness, double quality, boolean bestCaseContext) throws Exception {
		if (forest == null) {
			// load model
			File modelFile = new File(MODEL_PATH_LONG+"-"+wikipedia.getConfig().getLangCode()+".model"); 
			if (!modelFile.exists()) {
                logger.debug("Invalid model file for nerd ranker.");
			}
			String xml = FileUtils.readFileToString(modelFile, "UTF-8");
			if (model == MLModel.RANDOM_FOREST)
				forest = (RandomForest)xstream.fromXML(xml);
			else
				forest = (GradientTreeBoost)xstream.fromXML(xml);
			if (attributeDataset != null) 
				attributes = attributeDataset.attributes();
			else {
				StringBuilder arffBuilder = new StringBuilder();
				GenericRankerFeatureVector feat = new SimpleRankerFeatureVector();
				arffBuilder.append(feat.getArffHeader()).append("\n");
				arffBuilder.append(feat.printVector());
				String arff = arffBuilder.toString();
				attributeDataset = arffParser.parse(IOUtils.toInputStream(arff, "UTF-8"));
				attributes = attributeDataset.attributes();
				attributeDataset = null;
			}
			logger.info("Model for nerd ranker loaded: " + 
				MODEL_PATH_LONG+"-"+wikipedia.getConfig().getLangCode()+".model");
		}

		GenericRankerFeatureVector feature = new SimpleRankerFeatureVector();
		//feature.prob_c = commonness;
		feature.relatedness = relatedness;
		//feature.relatedness = 0.0;
		feature.context_quality = quality; 
		//feature.context_quality = 0.0; 
		//feature.dice_coef = dice_coef;
		feature.bestCaseContext = bestCaseContext;
		//feature.bestCaseContext = false;
		double[] features = feature.toVector(attributes);
		return forest.predict(features);
	}

	public void saveModel() throws IOException, Exception {
		logger.info("saving model");
		// save the model with XStream
		String xml = xstream.toXML(forest);
		File modelFile = new File(MODEL_PATH_LONG+"-"+wikipedia.getConfig().getLangCode()+".model"); 
		if (!modelFile.exists()) {
            logger.debug("Invalid file for saving author filtering model.");
		}
		FileUtils.writeStringToFile(modelFile, xml, "UTF-8");
		System.out.println("Model saved under " + modelFile.getPath());
	}

	public void loadModel() throws IOException, Exception {
		logger.info("loading model");
		// load model
		File modelFile = new File(MODEL_PATH_LONG+"-"+wikipedia.getConfig().getLangCode()+".model"); 
		if (!modelFile.exists()) {
        	logger.debug("Model file for nerd ranker does not exist.");
        	throw new NerdResourceException("Model file for nerd ranker does not exist.");
		}
		String xml = FileUtils.readFileToString(modelFile, "UTF-8");
		if (model == MLModel.RANDOM_FOREST)
			forest = (RandomForest)xstream.fromXML(xml);
		else
			forest = (GradientTreeBoost)xstream.fromXML(xml);
		logger.debug("Model for nerd ranker loaded.");
	}

	public void trainModel() throws Exception {
		if (attributeDataset == null) {
			logger.debug("Training data for nerd ranker has not been loaded or prepared");
			throw new NerdResourceException("Training data for nerd ranker has not been loaded or prepared");
		}
		logger.info("building model");
		double[][] x = attributeDataset.toArray(new double[attributeDataset.size()][]);
		double[] y = attributeDataset.toArray(new double[attributeDataset.size()]);
		
		long start = System.currentTimeMillis();
		if (model == MLModel.RANDOM_FOREST)
			forest = new RandomForest(attributeDataset.attributes(), x, y, 200);
		else {
			//nb trees: 200, maxNodes: 6, srinkage: 0.05, subsample: 0.5
			forest = new GradientTreeBoost(attributeDataset.attributes(), x, y, 
				GradientTreeBoost.Loss.LeastAbsoluteDeviation, 500, 6, 0.05, 0.5);
		}

        System.out.println("NERD ranker model created in " + 
			(System.currentTimeMillis() - start) / (1000.00) + " seconds");
	}

	public void train(ArticleTrainingSample articles, String datasetName) throws Exception {
		StringBuilder arffBuilder = new StringBuilder();
		GenericRankerFeatureVector feat = new SimpleRankerFeatureVector();
		arffBuilder.append(feat.getArffHeader()).append("\n");
		int nbArticle = 0;
		this.positives = 1;
		this.negatives = 0;
		for (Article article : articles.getSample()) {
			arffBuilder = trainArticle(article, arffBuilder);	
			nbArticle++;
System.out.println("nb article processed: " + nbArticle);
		}
//System.out.println(arffBuilder.toString());
		arffDataset = arffBuilder.toString();
		attributeDataset = arffParser.parse(IOUtils.toInputStream(arffDataset, "UTF-8"));
	}

	private StringBuilder trainArticle(Article article, StringBuilder arffBuilder) throws Exception {
		List<NerdEntity> refs = new ArrayList<NerdEntity>();
		String lang = wikipedia.getConfig().getLangCode();

		String content = MediaWikiParser.getInstance().
			toTextWithInternalLinksArticlesOnly(article.getFullWikiText(), lang);
		content = content.replace("''", "");
		StringBuilder contentText = new StringBuilder(); 
//System.out.println("Content: " + content);
		Pattern linkPattern = Pattern.compile("\\[\\[(.*?)\\]\\]"); 
		Matcher linkMatcher = linkPattern.matcher(content);

		// gather reference gold values
		int head = 0;
		while (linkMatcher.find()) {			
			String link = content.substring(linkMatcher.start()+2, linkMatcher.end()-2);
			if (head != linkMatcher.start())
				contentText.append(content.substring(head, linkMatcher.start()));
			String labelText = link;
			String destText = link;

			int pos = link.lastIndexOf('|');
			if (pos > 0) {
				destText = link.substring(0, pos);
				// possible anchor #
				int pos2 = destText.indexOf('#');
				if (pos2 != -1) {
					destText = destText.substring(0,pos2);
				}
				labelText = link.substring(pos+1);
			} else {
				// labelText and destText are the same, but we could have an anchor #
				int pos2 = link.indexOf('#');
				if (pos2 != -1) {
					destText = link.substring(0,pos2);
				} else {
					destText = link;
				}
				labelText = destText;
			}
			contentText.append(labelText);

			head = linkMatcher.end();
			
			Label label = new Label(wikipedia.getEnvironment(), labelText);
			Label.Sense[] senses = label.getSenses();
			if (destText.length() > 1)
				destText = Character.toUpperCase(destText.charAt(0)) + destText.substring(1);
			else {
				// no article considered as single letter
				continue;
			}
			Article dest = wikipedia.getArticleByTitle(destText);
			if ((dest != null) && (senses.length > 1)) {
				NerdEntity ref = new NerdEntity();
				ref.setRawName(labelText);
				ref.setWikipediaExternalRef(dest.getId());
				ref.setOffsetStart(contentText.length()-labelText.length());
				ref.setOffsetEnd(contentText.length());
	
				refs.add(ref);
//System.out.println(link + ", " + labelText + ", " + destText + " / " + ref.getOffsetStart() + " " + ref.getOffsetEnd());
			}
		}
		contentText.append(content.substring(head));
		String contentString = contentText.toString();
//System.out.println("Cleaned content: " + contentString);
		
		// get candidates for this content
		NerdEngine nerdEngine = NerdEngine.getInstance();
		Relatedness relatedness = Relatedness.getInstance();

		// process the text
		ProcessText processText = ProcessText.getInstance();
		List<Entity> entities = new ArrayList<Entity>();
		Language language = new Language(lang, 1.0);
		if (lang.equals("en") || lang.equals("fr")) {
			entities = processText.process(contentString, language);
		}
//System.out.println("number of NE found: " + entities.size());	
		List<Entity> entities2 = processText.processBrutal(contentString, language);
//System.out.println("number of non-NE found: " + entities2.size());	
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
//System.out.println("total entities to disambiguate: " + disambiguatedEntities.size());	

		Map<NerdEntity, List<NerdCandidate>> candidates = 
			nerdEngine.generateCandidates(disambiguatedEntities, lang);
//System.out.println("total entities with candidates: " + candidates.size());
		
		// set the expected concept to the NerdEntity
		for (Map.Entry<NerdEntity, List<NerdCandidate>> entry : candidates.entrySet()) {
			//List<NerdCandidate> cands = entry.getValue();
			NerdEntity entity = entry.getKey();

			/*for (NerdCandidate cand : cands) {
				System.out.println(cand.toString());
			}*/

			int start = entity.getOffsetStart();
			int end = entity.getOffsetEnd();
//System.out.println("entity: " + start + " / " + end + " - " + contentString.substring(start, end));
			for(NerdEntity ref : refs) {
				int start_ref = ref.getOffsetStart();
				int end_ref = ref.getOffsetEnd();
				if ( (start_ref == start) && (end_ref == end) ) {
					entity.setWikipediaExternalRef(ref.getWikipediaExternalRef());
					break;
				} 
			}
		}

		// get context for this content
//System.out.println("get context for this content");		
		NerdContext context = null;
		try {
			 context = relatedness.getContext(candidates, null, lang, false);
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
			if (expectedId == -1) {
				// we skip cases when no gold entity is present (nothing to rank against)
				continue;
			}
			if ((cands == null) || (cands.size() <= 1)) {
				// if no or only one candidate, nothing to rank and the example is not 
				// useful for training
				continue;
			}
			
			for(NerdCandidate candidate : cands) {
				try {
					nbCandidate++;
//System.out.println(nbCandidate + " candidate / " + cands.size());
					Label.Sense sense = candidate.getWikiSense();
					if (sense == null)
						continue;

					double commonness = sense.getPriorProbability();
//System.out.println("commonness: " + commonness);

					double related = relatedness.getRelatednessTo(candidate, context, lang);
//System.out.println("relatedness: " + related);

					boolean bestCaseContext = true;
					// actual label used
					Label bestLabel = candidate.getLabel();
					if (!entity.getNormalisedName().equals(bestLabel.getText())) {
						bestCaseContext = false;
					}

					GenericRankerFeatureVector feature = new SimpleRankerFeatureVector();
					//feature.prob_c = commonness;
					feature.relatedness = related;
					//feature.relatedness = 0.0;
					feature.context_quality = quality;
					//feature.context_quality = 0.0;
					feature.bestCaseContext = bestCaseContext;
					//feature.bestCaseContext = false;
					feature.label = (expectedId == candidate.getWikipediaExternalRef()) ? 1.0 : 0.0;

					// addition of the example is constrained by the sampling ratio
					if ( ((feature.label == 0.0) && ((double)this.negatives / this.positives < sampling)) ||
						 ((feature.label == 1.0) && ((double)this.negatives / this.positives >= sampling)) ) {
						arffBuilder.append(feature.printVector()).append("\n");
						nbInstance++;
						if (feature.label == 0.0)
							this.negatives++;
						else
							this.positives++;
					}
					
/*					System.out.println("*"+candidate.getWikiSense().getTitle() + "* " + 
							entity.toString());
					System.out.println("\t\t" + "commonness: " + commonness + 
						", relatedness: " + related + 
						", quality: " + quality);*/
				}
				catch(Exception e) {
					e.printStackTrace();
				}
			}
			Collections.sort(cands);
		}

		System.out.println("article contribution: " + nbInstance + " training instances");
		return arffBuilder;
	}

	public LabelStat evaluate(ArticleTrainingSample testSet) throws Exception {	
		List<LabelStat> stats = new ArrayList<LabelStat>();
		for (Article article : testSet.getSample()) {								
			stats.add(evaluateArticle(article));
		}
		return EvaluationUtil.evaluate(testSet, stats);
	}

	private LabelStat evaluateArticle(Article article) throws Exception {
//System.out.println(" - evaluating " + article);
		String lang = wikipedia.getConfig().getLangCode();
		String content = MediaWikiParser.getInstance()
			.toTextWithInternalLinksArticlesOnly(article.getFullWikiText(), lang);

		Pattern linkPattern = Pattern.compile("\\[\\[(.*?)\\]\\]"); 
		Matcher linkMatcher = linkPattern.matcher(content);

		Set<Integer> referenceDisamb = new HashSet<Integer>();
		Set<Integer> producedDisamb = new HashSet<Integer>();
		List<NerdEntity> referenceEntities = new ArrayList<NerdEntity>();
		List<String> labelTexts = new ArrayList<String>();
		int head = 0;
		StringBuilder contentText = new StringBuilder(); 
		while (linkMatcher.find()) {			
			String link = content.substring(linkMatcher.start()+2, linkMatcher.end()-2);
			if (head != linkMatcher.start())
				contentText.append(content.substring(head, linkMatcher.start()));
			String labelText = link;
			String destText = link;

			int pos = link.lastIndexOf('|');
			if (pos > 0) {
				destText = link.substring(0, pos);
				// possible anchor #
				int pos2 = destText.indexOf('#');
				if (pos2 != -1) {
					destText = destText.substring(0,pos2);
				}
				labelText = link.substring(pos+1);
			} else {
				// labelText and destText are the same, but we could have an anchor #
				int pos2 = link.indexOf('#');
				if (pos2 != -1) {
					destText = link.substring(0,pos2);
				} else {
					destText = link;
				}
				labelText = destText;
			}
			contentText.append(labelText);

			head = linkMatcher.end();
			
			//Label label = new Label(wikipedia.getEnvironment(), labelText);
			//Label.Sense[] senses = label.getSenses();
			if (destText.length() > 1)
				destText = Character.toUpperCase(destText.charAt(0)) + destText.substring(1);
			else {
				// no article considered as single letter
				continue;
			}
			Article dest = wikipedia.getArticleByTitle(destText);
			if ((dest != null)) {// && (senses.length > 0)) {
				NerdEntity ref = new NerdEntity();
				ref.setRawName(labelText);
				ref.setWikipediaExternalRef(dest.getId());
				ref.setOffsetStart(contentText.length()-labelText.length());
				ref.setOffsetEnd(contentText.length());

				referenceDisamb.add(dest.getId());
				referenceEntities.add(ref);
			}
		}

		contentText.append(content.substring(head));
		String contentString = contentText.toString();

		// be sure to have the entities to be ranked
		List<Entity> nerEntities = new ArrayList<Entity>();
		for(NerdEntity refEntity : referenceEntities) {
			Entity localEntity = new Entity(refEntity.getRawName());
			localEntity.setOffsetStart(refEntity.getOffsetStart());
			localEntity.setOffsetEnd(refEntity.getOffsetEnd());
			nerEntities.add(localEntity);
		}
		List<NerdEntity> entities = new ArrayList<NerdEntity>();
		for (Entity entity : nerEntities) {
			NerdEntity theEntity = new NerdEntity(entity);
			entities.add(theEntity);
		}
		// the entity for evaluation
		List<NerdEntity> evalEntities = new ArrayList<NerdEntity>();
		for (Entity entity : nerEntities) {
			NerdEntity theEntity = new NerdEntity(entity);
			evalEntities.add(theEntity);
		}

		// process the text for building actual context for evaluation
		ProcessText processText = ProcessText.getInstance();
		nerEntities = new ArrayList<Entity>();
		Language language = new Language(lang, 1.0);
		if (lang.equals("en") || lang.equals("fr")) {
			nerEntities = processText.process(contentString, language);
		}
		for(Entity entity : nerEntities) {
			// we add entities only if the mention is not already present
			NerdEntity theEntity = new NerdEntity(entity);
			if (!entities.contains(theEntity))
				entities.add(theEntity);
		}
		//System.out.println("number of NE found: " + entities.size());	
		// add non NE terms
		List<Entity> entities2 = processText.processBrutal(contentString, language);
//System.out.println("number of non-NE found: " + entities2.size());	
		for(Entity entity : entities2) {
			// we add entities only if the mention is not already present
			NerdEntity theEntity = new NerdEntity(entity);
			if (!entities.contains(theEntity))
				entities.add(theEntity);
		}

		NerdEngine engine = NerdEngine.getInstance();
		Map<NerdEntity, List<NerdCandidate>> candidates = 
			engine.generateCandidates(entities, wikipedia.getConfig().getLangCode());	
		engine.rank(candidates, wikipedia.getConfig().getLangCode(), null, false);
		for (Map.Entry<NerdEntity, List<NerdCandidate>> entry : candidates.entrySet()) {
			List<NerdCandidate> cands = entry.getValue();
			NerdEntity entity = entry.getKey();
			if (cands.size() > 0) {
				// check that we have a reference result for the same chunck
				int start = entity.getOffsetStart();
				int end = entity.getOffsetEnd(); 
				boolean found = false;
				for(NerdEntity refEntity : referenceEntities) {
					int startRef = refEntity.getOffsetStart();
					int endRef = refEntity.getOffsetEnd(); 
					if ((start == startRef) && (end == endRef)) {
						found = true;
						//producedDisamb.add(new Integer(refEntity.getWikipediaExternalRef()));
						break;
					}
				}
				if (found) {
					producedDisamb.add(new Integer(cands.get(0).getWikipediaExternalRef()));
				}
			}
		}
 
		LabelStat stats = new LabelStat();
		stats.setObserved(producedDisamb.size());
		for(Integer index : producedDisamb) {
			if (!referenceDisamb.contains(index)) {
				stats.incrementFalsePositive();
			}
		}

		stats.setExpected(referenceDisamb.size());
		for(Integer index : referenceDisamb) {
			if (!producedDisamb.contains(index)) {
				stats.incrementFalseNegative();
			}
		}

		return stats;
	}

}
