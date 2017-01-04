/**
 * http://surenpi.com
 */
package org.suren.autotest.web.framework.code;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.VisitorSupport;
import org.dom4j.XPath;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.SAXReader;
import org.dom4j.io.XMLWriter;
import org.dom4j.xpath.DefaultXPath;
import org.jaxen.SimpleNamespaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.xml.sax.SAXException;

/**
 * 根据元素定位的xml描述信息生成xml格式的数据源文件
 * @author suren
 * @date 2016年12月14日 上午8:21:27
 */
public class DefaultXmlDataSourceGenerator implements Generator
{
	private static final Logger logger = LoggerFactory.getLogger(DefaultXmlDataSourceGenerator.class);

	private String outputDir;
	private Map<String, Document> docMap;
	
	@Override
	public void generate(String srcCoding, String outputDir)
	{
		this.outputDir = outputDir;
		
		ClassLoader classLoader = this.getClass().getClassLoader();
		docMap = new HashMap<String, Document>();
		
		//读取主配置文件
		try(InputStream confInput = classLoader.getResourceAsStream(srcCoding))
		{
			read(confInput);
		}
		catch (DocumentException | IOException e)
		{
			logger.error(String.format("Main config [%s] parse process error.", srcCoding), e);
		}
		catch (SAXException e)
		{
			e.printStackTrace();
		}
		finally
		{
			for(String resPath : docMap.keySet())
			{
				Document doc = docMap.get(resPath);
				write(doc, resPath);
			}
		}
	}

	/**
	 * 从流中读取配置文件
	 * 
	 * @param inputStream
	 * @throws DocumentException
	 * @throws IOException 
	 * @throws SAXException 
	 */
	public void read(InputStream inputStream) throws DocumentException, IOException, SAXException
	{
		Document document = new SAXReader().read(inputStream);

		parse(document);
	}

	/**
	 * @param document
	 */
	private void parse(Document doc)
	{
		SimpleNamespaceContext simpleNamespaceContext = new SimpleNamespaceContext();
		simpleNamespaceContext.addNamespace("ns", "http://surenpi.com");
		
		XPath xpath = new DefaultXPath("/ns:autotest/ns:includePage");
		xpath.setNamespaceContext(simpleNamespaceContext);
		@SuppressWarnings("unchecked")
		List<Element> includePageList = xpath.selectNodes(doc);
		if(includePageList != null && includePageList.size() > 0)
		{
			for(Element includePage : includePageList)
			{
				String pageConfig = includePage.attributeValue("pageConfig");
				
				generate(pageConfig, outputDir);
			}
		}
		
		xpath = new DefaultXPath("/ns:autotest/ns:pages");
		xpath.setNamespaceContext(simpleNamespaceContext);
		Element pagesEle = (Element) xpath.selectSingleNode(doc);
		String pagePackage = pagesEle.attributeValue("pagePackage", "");
		if(StringUtils.isNotBlank(pagePackage))
		{
			pagePackage = (pagePackage.trim() + ".");
		}

		// pages parse progress
		xpath = new DefaultXPath("/ns:autotest/ns:pages/ns:page");
		xpath.setNamespaceContext(simpleNamespaceContext);
		@SuppressWarnings("unchecked")
		List<Element> pageNodes = xpath.selectNodes(doc);
		if (pageNodes != null)
		{
			for (Element ele : pageNodes)
			{
				String pageClsStr = ele.attributeValue("class");
				if (pageClsStr == null)
				{
					logger.warn("can not found class attribute.");
					continue;
				}

				pageClsStr = (pagePackage + pageClsStr);
				String dataSrc = ele.attributeValue("dataSource");

				try
				{
					parse(doc, pageClsStr, dataSrc, ele);
				}
				catch (NoSuchBeanDefinitionException e)
				{
					logger.error("Page element [{}] parse error, in document [{}].", "pageClsStr", doc);
					throw e;
				}
				catch (Exception e)
				{
					logger.error("Page element parse error.", e);
				}
			}
		}
	}

	/**
	 * 解析页面Page对象
	 * 
	 * @param pageClsStr
	 * @param dataSrc
	 * @param ele
	 */
	private void parse(Document doc, final String pageClsStr, String dataSrc,
			Element ele) throws Exception
	{
		SimpleNamespaceContext simpleNamespaceContext = new SimpleNamespaceContext();
		simpleNamespaceContext.addNamespace("ns", "http://surenpi.com");
		
		XPath xpath = new DefaultXPath("/ns:autotest/ns:dataSources/ns:dataSource[@name='" + dataSrc + "']");
		xpath.setNamespaceContext(simpleNamespaceContext);
		Element dataSourceEle = (Element) xpath.selectSingleNode(doc);
		if(dataSourceEle == null)
		{
			logger.error("Can not found dataSource element by {}.", dataSrc);
			return;
		}
		
		String dsType = dataSourceEle.attributeValue("type");
		String dsResource = dataSourceEle.attributeValue("resource");
		
		logger.debug("DataSource type is {}, resource is {}.", dsType, dsResource);
		
		updateXmlDataSourceByEle(ele, dsResource, pageClsStr);
	}

	/**
	 * @param ele
	 */
	private void updateXmlDataSourceByEle(Element ele, String dsResource, String pageClsStr)
	{
		Document doc = docMap.get(dsResource);
		if(doc == null)
		{
			doc = prepareDoc(dsResource);
			docMap.put(dsResource, doc);
		}
		
		SimpleNamespaceContext simpleNamespaceContext = new SimpleNamespaceContext();
		simpleNamespaceContext.addNamespace("ns", "http://datasource.surenpi.com");
		
		XPath xpath = new DefaultXPath("/ns:dataSources");
		xpath.setNamespaceContext(simpleNamespaceContext);
		
		Element dataSourcesEle = doc.getRootElement();
		
		//先查找是否有该标签
		xpath = new DefaultXPath("/ns:dataSources/ns:dataSource[@pageClass='" + pageClsStr + "']");
		xpath.setNamespaceContext(simpleNamespaceContext);
		Element dataSourceEle = (Element) xpath.selectSingleNode(doc);
		if(dataSourceEle == null)
		{
			String prefix = dataSourcesEle.getNamespacePrefix();
			if(StringUtils.isBlank(""))
			{
				String parentName = dataSourcesEle.getName();
				if(parentName.contains(":"))
				{
					prefix = parentName.split(":")[0];
				}
			}
			
			if(StringUtils.isNotBlank(prefix))
			{
				prefix = prefix + ":";
			}
			
			dataSourceEle = dataSourcesEle.addElement(prefix + "dataSource");
			
			dataSourceEle.addAttribute("pageClass", pageClsStr);
		}
		
		//只更新第一个子标签
		xpath = new DefaultXPath("/ns:dataSources/ns:dataSource[@pageClass='" + pageClsStr + "']/ns:page[1]");
		xpath.setNamespaceContext(simpleNamespaceContext);
		Element pageEle = (Element) xpath.selectSingleNode(dataSourceEle);
		if(pageEle == null)
		{
			String prefix = dataSourceEle.getNamespacePrefix();
			if(StringUtils.isNotBlank(prefix))
			{
				prefix = prefix + ":";
			}
			
			pageEle = dataSourceEle.addElement(prefix + "page");
		}
		
		ele.accept(new PageFieldVisitor(pageEle, pageClsStr));
	}
	
	/**
	 * 根据给定的路径准备好对应的xml文档对象（document），会把根元素添加好
	 * @param resPath
	 * @return
	 */
	private Document prepareDoc(final String resPath)
	{
		ClassLoader clsLoader = this.getClass().getClassLoader();
		URL url = clsLoader.getResource(resPath);
		InputStream dsInput = null;
		try
		{
			if(url != null)
			{
				dsInput = url.openStream();
			}
		}
		catch (IOException e1)
		{
			e1.printStackTrace();
		}
		
		Document doc = null;
		if(dsInput != null)
		{
			try
			{
				doc = new SAXReader().read(dsInput);
			}
			catch (DocumentException e)
			{
				e.printStackTrace();
			}
		}
		else
		{
			doc = DocumentHelper.createDocument();
		}

		SimpleDateFormat dateFormat = new SimpleDateFormat();
		doc.addComment("Auto created by AutoTest, " + dateFormat.format(new Date()));
		
		SimpleNamespaceContext simpleNamespaceContext = new SimpleNamespaceContext();
		simpleNamespaceContext.addNamespace("ns", "http://datasource.surenpi.com");
		
		XPath xpath = new DefaultXPath("/ns:dataSources");
		xpath.setNamespaceContext(simpleNamespaceContext);
		
		//先查找是否有该标签
		Element dataSourcesEle = (Element) xpath.selectSingleNode(doc);
		if(dataSourcesEle == null)
		{
			String prefix = "suren";
			dataSourcesEle = doc.addElement(prefix + ":dataSources");
			
			dataSourcesEle.addNamespace(prefix, "http://datasource.surenpi.com");
			dataSourcesEle.addAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
			dataSourcesEle.addAttribute("xsi:schemaLocation", "http://datasource.surenpi.com "
					+ "http://surenpi.com/schema/datasource/autotest.web.framework.datasource.xsd ");
		}
		
		return doc;
	}
	
	/**
	 * xml文档回写
	 * @see #write(Document, OutputFormat, String)
	 * @param doc
	 * @param resPath
	 */
	private void write(final Document doc, final String resPath)
	{
		OutputFormat outputFormat = OutputFormat.createPrettyPrint();
		outputFormat.setIndentSize(4);
		
		write(doc, outputFormat, resPath);
	}
	
	/**
	 * xml文档回写
	 * @param doc 文档实例对象
	 * @param format 格式
	 * @param resPath 输出路径
	 */
	private void write(final Document doc, final OutputFormat format, final String resPath)
	{
		ClassLoader clsLoader = this.getClass().getClassLoader();
		URL url = clsLoader.getResource(resPath);
		
		String outputFileName = null;
		if(url != null)
		{
			outputFileName = new File(url.getFile()).getName();
		}
		else
		{
			outputFileName = new File(resPath).getName();
		}

		File outputDirFile = new File(outputDir);
		if(!outputDirFile.isDirectory())
		{
			outputDirFile.mkdirs();
		}
		
		try(OutputStream dsOutput = new FileOutputStream(new File(outputDirFile, outputFileName)))
		{
			OutputFormat outputFormat = OutputFormat.createPrettyPrint();
			outputFormat.setIndentSize(4);
			XMLWriter xmlWriter = new XMLWriter(dsOutput, outputFormat);
			
			xmlWriter.write(doc);
		}
		catch (FileNotFoundException e)
		{
			e.printStackTrace();
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}

	class PageFieldVisitor extends VisitorSupport
	{
		private SimpleNamespaceContext simpleNamespaceContext = new SimpleNamespaceContext();
		private Element pageEle;
		private String pageClsStr;
		
		public PageFieldVisitor(Element pageEle, String pageClsStr)
		{
			this.pageEle = pageEle;
			this.pageClsStr = pageClsStr;
			simpleNamespaceContext.addNamespace("ns", "http://datasource.surenpi.com");
		}

		@Override
		public void visit(Element node)
		{
			if (!"field".equals(node.getName()))
			{
				return;
			}

			String fieldName = node.attributeValue("name");
			String data = node.attributeValue("data", "");
			String type = node.attributeValue("type");
			if (fieldName == null || "".equals(fieldName)
					|| "button".equals(type))
			{
				return;
			}
			
			XPath xpath = new DefaultXPath("/ns:dataSources/ns:dataSource[@pageClass='" + pageClsStr + "']/ns:page[1]/ns:field[@name='" + fieldName + "']");
			xpath.setNamespaceContext(simpleNamespaceContext);
			
			Element fieldEle = (Element) xpath.selectSingleNode(pageEle);
			if(fieldEle == null)
			{
				String prefix = pageEle.getNamespacePrefix();
				if(StringUtils.isNotBlank(prefix))
				{
					prefix = prefix + ":";
				}
				
				fieldEle = pageEle.addElement(prefix + "field");
				
				fieldEle.addAttribute("name", fieldName);
				fieldEle.addAttribute("data", data);
			}
		}
	}
}
