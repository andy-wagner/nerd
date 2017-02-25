package com.scienceminer.nerd.kb;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.io.*;

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.Ignore; 

import com.scienceminer.nerd.utilities.NerdProperties;
import com.scienceminer.nerd.utilities.NerdPropertyKeys;

import com.scienceminer.nerd.kb.db.*;
import org.wikipedia.miner.model.*;
import org.wikipedia.miner.util.*;

import org.xml.sax.SAXException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 *  @author Patrice Lopez
 */
public class TestLookup {
	
	private Wikipedia wikipedia = null;

	@Before
	public void setUp() {
		try {
			NerdProperties.getInstance();
			WikipediaConfiguration conf = 
					new WikipediaConfiguration(new File("data/wikipedia/wikipedia-en.xml"));
        	wikipedia = new Wikipedia(conf, false); // no distinct thread for accessing data
        } catch(Exception e) {
        	e.printStackTrace();
        }
	}
   	
	@Test
	public void testPageId() {
		try {
			Page page = wikipedia.getPageById(3966054);

			System.out.println("pageId:" + page.getId());
			System.out.println(page.getTitle());
		}
		catch(Exception e) {
			e.printStackTrace();
		}
	}
	
	@Test
	public void testTitle() {
		try {
			Page page = wikipedia.getArticleByTitle("Mexico");
			if (page != null) {
				System.out.println(page.getTitle());
				System.out.println("pageId:" + page.getId());
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}
	}
	

	@After
	public void testClose() {
		try {
			wikipedia.close();
		}
		catch(Exception e) {
			e.printStackTrace();
		}
	}
}

