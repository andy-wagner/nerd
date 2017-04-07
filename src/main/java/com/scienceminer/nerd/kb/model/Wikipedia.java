package com.scienceminer.nerd.kb.model;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import javax.xml.parsers.ParserConfigurationException;
import org.apache.log4j.Logger;

import com.scienceminer.nerd.kb.db.*;
import com.scienceminer.nerd.kb.db.KBEnvironment.StatisticName;
import com.scienceminer.nerd.utilities.NerdConfig;

import org.wikipedia.miner.db.struct.DbLabel;
import org.wikipedia.miner.db.struct.DbIntList;

import com.scienceminer.nerd.kb.model.Page.PageType;

import org.wikipedia.miner.util.NGrammer.CaseContext;
import org.wikipedia.miner.util.NGrammer.NGramSpan;
import org.xml.sax.SAXException;

/**
 * Represent a language speccific instance of Wikipedia
 */
public class Wikipedia {

	private KBEnvironment env = null;
	private int wikipediaArticleCount = -1;

	public enum LinkDirection {
		In, 
		Out
	}

	/**
	 * Initialises a newly created Wikipedia according to the given configuration. 
	 *  
	 * @param conf a Nerd configuration 
	 */
	public Wikipedia(NerdConfig conf) {
		this.env = new KBEnvironment(conf);
		try {
			//this.env.buildEnvironment(conf, conf.getDataDirectory(), false);
			this.env.buildEnvironment(conf, false);
		} catch(Exception e) {
			e.printStackTrace();
		} 
	}

	public int getArticleCount() {
		if (wikipediaArticleCount == -1)
			wikipediaArticleCount = new Long(this.env.retrieveStatistic(StatisticName.articleCount)).intValue();
		return wikipediaArticleCount;
	}

	/**
	 * Returns the environment that this is connected to
	 * 
	 * @return the environment that this is connected to
	 */
	public KBEnvironment getEnvironment() {
		return env;
	}

	/**
	 * Returns the configuration of this wikipedia dump
	 * 
	 * @return the configuration of this wikipedia dump
	 */
	public NerdConfig getConfig() {
		return env.getConfiguration();
	}

	/**
	 * Returns the root Category from which all other categories can be browsed.
	 * 
	 * @return the root category
	 */
	public Category getRootCategory() {
		return new Category(env, env.retrieveStatistic(StatisticName.rootCategoryId).intValue());
	}

	/**
	 * Returns the Page referenced by the given id. The page can be cast into the appropriate type for 
	 * more specific functionality. 
	 *  
	 * @param id	the id of the Page to retrieve.
	 * @return the Page referenced by the given id, or null if one does not exist. 
	 */
	public Page getPageById(int id) {
		return Page.createPage(env, id);
	}

	/**
	 * Returns the Article referenced by the given (case sensitive) title. If the title
	 * matches a redirect, this will be resolved to return the redirect's target.
	 * <p>
	 * The given title must be matched exactly to return an article. If you want some more lee-way,
	 * use getMostLikelyArticle() instead. 
	 *  
	 * @param title	the title of an Article (or its redirect).
	 * @return the Article referenced by the given title, or null if one does not exist
	 */
	public Article getArticleByTitle(String title) {
		if (title == null || title.length() == 0)
			return null;

		title = title.substring(0,1).toUpperCase() + title.substring(1);
		Integer id = env.getDbArticlesByTitle().retrieve(title);

		if (id == null)
			return null;

		Page page = Page.createPage(env, id);
		if (!page.exists())
			return null;

		if (page.getType() == PageType.redirect)
			return ((Redirect)page).getTarget();
		else
			return (Article)page;
	}

	/**
	 * Returns the Category referenced by the given (case sensitive) title. 
	 * 
	 * The given title must be matched exactly to return a Category. 
	 *  
	 * @param title	the title of an Article (or it's redirect).
	 * @return the Article referenced by the given title, or null if one does not exist
	 */
	public Category getCategoryByTitle(String title) {
		title = title.substring(0,1).toUpperCase() + title.substring(1);
		Integer id = env.getDbCategoriesByTitle().retrieve(title);

		if (id == null)
			return null;

		Page page = Page.createPage(env, id);
		if (page.getType() == PageType.category)
			return (Category) page;
		else
			return null;
	}

	/**
	 * Returns the Template referenced by the given (case sensitive) title. 
	 * 
	 * The given title must be matched exactly to return a Template. 
	 *  
	 * @param title the title of a Template.
	 * @return the Template referenced by the given title, or null if one does not exist
	 */
	public Template getTemplateByTitle(String title) {
		title = title.substring(0,1).toUpperCase() + title.substring(1);
		Integer id = env.getDbTemplatesByTitle().retrieve(title);

		if (id == null)
			return null;

		Page page = Page.createPage(env, id);
		if (page.getType() == PageType.template)
			return (Template) page;
		else
			return null;
	}


	/**
	 * Returns the most likely article for a given term. For example, searching for "tree" will return
	 * the article "30579: Tree", rather than "30806: Tree (data structure)" or "7770: Christmas tree"
	 * This is defined by the number of times the term is used as an anchor for links to each of these 
	 * destinations. 
	 *  <p>
	 * An optional text processor (may be null) can be used to alter the way labels are 
	 * retrieved (e.g. via stemming or case folding) 
	 * 
	 * @param term	the term to obtain articles for
	 * 
	 * @return the most likely sense of the given term.
	 * 
	 * for the given text processor.
	 */
	public Article getMostLikelyArticle(String term){
		Label label = new Label(env, term);
		if (!label.exists()) 
			return null;

		return label.getSenses()[0];
	}

	/**
	 * A convenience method for quickly finding out if the given text is ever used as a label
	 * in Wikipedia. If this returns false, then all of the getArticle methods will return null or empty sets. 
	 * 
	 * @param text the text to search for
	 * @return true if there is an anchor corresponding to the given text, otherwise false
	 */
	public boolean isLabel(String text)  {
		DbLabel lbl = env.getDbLabel().retrieve(text); 

		return lbl != null;
	}
	
	public Label getLabel(NGramSpan span, String sourceText) {
		String ngram = span.getNgram(sourceText);
		Label bestLabel = getLabel(ngram);
		
		//don't bother trying out different casing variations if we are using casefolder as text processor
		//TextProcessor tp = getConfig().getDefaultTextProcessor();
		//if (tp != null && (tp.class. == TextProcessor.class)
		///	return bestLabel;
		
		//if this starts with capital letter and is at start of sentence, try lower-casing that first letter.
		if (span.getCaseContext() == CaseContext.mixed && span.isSentenceStart() && Character.isUpperCase(ngram.charAt(0))) {
			//System.out.println("trying lower first letter first token");
			char tmpNgram[] = ngram.toCharArray();
			tmpNgram[0] = Character.toLowerCase(tmpNgram[0]);
			
			Label label = getLabel(new String(tmpNgram));
			
			//System.out.println(label.getText());
			
			if (label.exists() && (!bestLabel.exists() || label.getLinkOccCount() > bestLabel.getLinkOccCount())) {
				bestLabel = label;
				//System.out.println("using lower first letter first token");
			}
		}
			
		//if surrounding text is all lower case or ALL UPPER CASE, try with First Letter Of Each Token Uppercased.
		if (span.getCaseContext() == CaseContext.lower || span.getCaseContext() == CaseContext.upper) {
			//System.out.println("trying upper first letter all tokens");
			Label label = getLabel(span.getNgramUpperFirst(sourceText));
			
			if (label.exists() && (!bestLabel.exists() || label.getLinkOccCount() > bestLabel.getLinkOccCount())) {
				bestLabel = label;
				//System.out.println("using upper first letter all tokens");
			}
		}
		
		//if surrounding text is ALL UPPER CASE or Has First Letter Of Each Token Uppercased, try with all lower case 
		if (span.getCaseContext() == CaseContext.upperFirst || span.getCaseContext() == CaseContext.upper) {
			//System.out.println("trying lower");
			Label label = getLabel(ngram.toLowerCase());
			
			if (label.exists() && (!bestLabel.exists() || label.getLinkOccCount() > bestLabel.getLinkOccCount())) {
				bestLabel = label;
				//System.out.println("using lower");
			}
		}
		
		//if surrounding text is all lower case, try with ALL UPPER CASE
		if (span.getCaseContext() == CaseContext.lower) {
			//System.out.println("trying upper");
			Label label = getLabel(ngram.toUpperCase());
			
			if (label.exists() && (!bestLabel.exists() || label.getLinkOccCount() > bestLabel.getLinkOccCount())) {
				//System.out.println("using upper");
				bestLabel = label;
			}
		}
		
		return bestLabel;
	}

	public Label getLabel(String text)  {
		return new Label(env, text);
	}

	/**
	 * Returns an iterator for all pages in the database, in order of ascending ids.
	 * 
	 * @return an iterator for all pages in the database, in order of ascending ids.
	 */
	public PageIterator getPageIterator() {
		return new PageIterator(env);
	}

	/**
	 * Returns an iterator for all pages in the database of the given type, in order of ascending ids.
	 * 
	 * @param type the type of page of interest
	 * @return an iterator for all pages in the database of the given type, in order of ascending ids.
	 */
	public PageIterator getPageIterator(PageType type) {
		return new PageIterator(env, type);		
	}

	/**
	 * Returns an iterator for all labels in the database, processed according to the given text processor (may be null), in alphabetical order.
	 * 
	 * @return an iterator for all labels in the database, processed according to the given text processor (may be null), in alphabetical order.
	 */
	public LabelIterator getLabelIterator() {
		return new LabelIterator(env);
	}

	/**
	 * Returns the list of links in relation to artId with the specified direction (in or out).
	 * 
	 */
	public ArrayList<Integer> getLinks(int artId, LinkDirection dir) {
		DbIntList ids = null;
		if (dir == LinkDirection.In)
			ids = env.getDbPageLinkInNoSentences().retrieve(artId);
		else
			ids = env.getDbPageLinkOutNoSentences().retrieve(artId);

		if (ids == null || ids.getValues() == null) 
			return new ArrayList<Integer>();

		return ids.getValues();
	}


	public void close() {
		env.close();
		this.env = null;
	}

	@Override
	public void finalize() {
        try {
            if (this.env != null)
                Logger.getLogger(Wikipedia.class).warn("Unclosed wikipedia. You may be causing a memory leak.");
        } finally {
            try {
                super.finalize();
            } catch (Throwable ex) {
                Logger.getLogger(Wikipedia.class).warn("Unclosed wikipedia. You may be causing a memory leak.");
            }
        }
	}
}
