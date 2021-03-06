package com.jazzautomation;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.io.Resources;
import com.jazzautomation.action.PageAction;
import com.jazzautomation.page.DomElement;
import com.jazzautomation.page.Page;
import com.jazzautomation.ui.Browsers;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Proxy.ProxyType;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.safari.SafariDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.jazzautomation.util.Constants.*;

/**
 * UIManager holds all pages and their web components, page actions. It loads all configurations from settings.properties then go through all
 * registeredPages to load page object classes as well as their xml configurations.
 */
@SuppressWarnings({
                    "AssignmentToStaticFieldFromInstanceMethod",
                    "UnusedDeclaration",
                    "TypeMayBeWeakened",
                    "StaticMethodOnlyUsedInOneClass",
                    "KeySetIterationMayUseEntrySet"
                  })
public class WebUIManager
{
  private static final Logger LOG                       = LoggerFactory.getLogger(WebUIManager.class);
  private static final String VERSION                   = "version";
  public static final String  SYSTEM_BROWSERS_SETTING   = "browsers";
  public static final String  SYSTEM_REPORTS_PATH       = "jazz.reports";
  public static final String  SYSTEM_CONFIGURATION_PATH = "jazz.configs";
  public static final String  WEBDRIVER_CHROME_DRIVER   = "webdriver.chrome.driver";
  private static WebUIManager instance;

  // TODO - Move these to method level variables, or move this class away from a Singleton.
  private Map<String, Page>             pages          = new HashMap<>();
  private Map<String, DomElement>       domElementPool = new HashMap<>();
  private Map<String, List<PageAction>> pageActions    = new HashMap<>();

  // configurations and reportsPath
  private String configurationsPath;
  private String reportsPath;

  // from jazz.properties
  private String        projectName        = "";
  private boolean       useRemoteWebDriver;
  private static String remoteWebDriverUrl;
  private static int    pageLoadTimeout    = 10;
  private static int    actionPace         = 1;
  private String        customClasspath;
  private static String featureNames;

  // from system properties
  private Browsers   browser        = null;
  private String     platform       = null;
  private String     browserVersion = null;
  private Properties settings       = new Properties();

  /**
   * Singleton instance of UIManager to hold information of pages and page actions.
   *
   * @return  The singleton UIManager
   *
   * @throws  IOException
   */
  public static WebUIManager getInstance()
  {
    // jsheridan CODEREVIEW - Seems kludgy - can't you use Spring to get a singleton?
    if (instance == null)
    {
      instance = new WebUIManager();
    }

    return instance;
  }

  /** called by the UI to reintialize the driver code so subsequent invocations will work. */
  public static void reinitialize()
  {
    instance = null;
    getInstance();
  }

  /*
   * private constructor.
   */
  private WebUIManager()
  {
    long startTime = System.currentTimeMillis();

    configurationsPath = System.getProperty(SYSTEM_CONFIGURATION_PATH);
    reportsPath        = System.getProperty(SYSTEM_REPORTS_PATH);
    browser            = getBrowserFromSystemProperty();

    String useRemote   = System.getProperty("remote");

    useRemoteWebDriver = BooleanUtils.toBoolean(useRemote);

    File configurationsFile = StringUtils.isNotEmpty(configurationsPath) ? new File(configurationsPath)
                                                                         : new File("configurations");

    configurationsPath = configurationsFile.getAbsolutePath();
    getOrCreateLogsPath();
    LOG.info("Initializing Jazz Automation configuration sub-system");

    // read settings files:
    LOG.info("Reading jazz.properties from [" + configurationsPath + File.separator + "jazz.properties]");
    LOG.info("Accessing reports directory at [" + reportsPath + "]");

    try(InputStream stream = new FileInputStream(new File(configurationsPath, JAZZ + ".properties")))
    {
      settings.load(stream);
      loadConfiguration();

      long endTime = System.currentTimeMillis();

      LOG.info("Jazz Automation started successfully in [" + (endTime - startTime) + "] milliseconds.");
    }
    catch (FileNotFoundException e)
    {
      LOG.error("An unexpected error has occurred; a configuration file could not be found.", e);
    }
    catch (IOException e)
    {
      LOG.error("An unexpected error has occurred.", e);
    }
  }

  private Browsers getBrowserFromSystemProperty()
  {
    Optional<Browsers> possible = Browsers.findValueOf(System.getProperty(SYSTEM_BROWSERS_SETTING));

    if (possible.isPresent())
    {
      return possible.get();
    }

    return Browsers.NOT_SPECIFIED;
  }

  private void getOrCreateLogsPath()
  {
    File logsFile;

    if (StringUtils.isNotEmpty(reportsPath))
    {
      logsFile = new File(reportsPath);

      // recursively create file path if needed
      if (!logsFile.exists())
      {
        logsFile.mkdirs();
      }
    }
    else
    {
      logsFile = new File("reports");
    }

    reportsPath = logsFile.getAbsolutePath();
  }

  // getter and setters
  public Map<String, List<PageAction>> getPageActions()
  {
    return pageActions;
  }

  public List<PageAction> getPageActionListFromPage(String pageName)
  {
    return pageActions.get(pageName);
  }

  public DomElement getDomElementFromPool(String identifier)
  {
    return domElementPool.get(identifier);
  }

  /**
   * Given a page name, return WebPage object in the Map of pages.
   *
   * @param   pageName  of the WebPage.
   *
   * @return  WebPage.
   */
  public Page getPage(String pageName)
  {
    return pages.get(pageName);
  }

  /**
   * loading setting.properties and populate all pages from its configurations.
   *
   * @throws  FileNotFoundException
   */
  private void loadConfiguration() throws IOException
  {
    if (StringUtils.isNotEmpty(settings.getProperty(PROJECT_NAME)))
    {
      projectName = settings.getProperty(PROJECT_NAME);
    }

    if (StringUtils.isNotEmpty(settings.getProperty(USE_REMOTE)))
    {
      useRemoteWebDriver = Boolean.valueOf(settings.getProperty(USE_REMOTE));
    }

    if (useRemoteWebDriver)
    {
      if (StringUtils.isNotEmpty(settings.getProperty(REMOTE_WEB_DRIVER_URL)))
      {
        remoteWebDriverUrl = settings.getProperty(REMOTE_WEB_DRIVER_URL);
      }
      else
      {
        LOG.error("When setting 'useRemote' to true, you must specify property: remoteWebDriverUrl.");
        System.exit(0);
      }
    }

    boolean useXml = false;

    if (StringUtils.isNotEmpty(settings.getProperty(SETTINGS_USE_XML)))
    {
      useXml = Boolean.valueOf(settings.getProperty(SETTINGS_USE_XML));
    }

    String pagesDirectoryName = settings.getProperty(PAGES_DIRECTORY_NAME);

    // by default, use pages as the folder name
    pagesDirectoryName = (StringUtils.isEmpty(pagesDirectoryName)) ? "pages"
                                                                   : pagesDirectoryName;

    String pagesFolderPath = configurationsPath + File.separator + pagesDirectoryName;

    if (settings.getProperty(PAGE_LOAD_TIMEOUT) != null)
    {
      pageLoadTimeout = Integer.parseInt(settings.getProperty(PAGE_LOAD_TIMEOUT).trim());
    }

    if (settings.getProperty(ACTION_PACE) != null)
    {
      actionPace = Integer.parseInt(settings.getProperty(ACTION_PACE).trim());
    }

    if (settings.getProperty(FEATURE_NAMES_EXECUTION) != null)
    {
      featureNames = settings.getProperty(FEATURE_NAMES_EXECUTION);
    }

    if (settings.getProperty(BROWSER) != null)
    {
      Optional<Browsers> possible = Browsers.findValueOf(settings.getProperty(BROWSER));

      if (possible.isPresent())
      {
        browser = possible.get();  // only override if it exists
      }
    }

    if (StringUtils.isNotBlank(settings.getProperty(CUSTOM_CLASSPATH)))
    {
      customClasspath = settings.getProperty(CUSTOM_CLASSPATH).trim();
    }

    // loop through directory, if useXml, add all files with xml postfix to pages, otherwise add all json files
    File pageFolder = new File(pagesFolderPath);

    if (!pageFolder.isDirectory())
    {
      LOG.error("'Pages' folder must be a directory, [" + pageFolder.getAbsolutePath() + "] is invalid.");
      throw new RuntimeException('\'' + pageFolder.getAbsolutePath() + "' must be a directory. ");
    }

    List<String> fileNames = Arrays.asList(pageFolder.list());

    // sort into alphabetical order so that we can always have override features for web components
    Collections.sort(fileNames);
    LOG.info("Processing configuration files at " + configurationsPath + File.separator + "pages" + File.separator + ":");

    for (String fileName : fileNames)
    {
      addPage(useXml, pagesFolderPath, fileName);
    }
  }

  /** add page entry with DOM elements. */
  private void addPage(boolean useXml, String pagesFolderPath, String fileName) throws IOException
  {
    try(FileInputStream fileIn = new FileInputStream(pagesFolderPath + File.separator + fileName))
    {
      LOG.info("Processing page configuration file: " + fileName + ", useXml = " + useXml);

      Page page;

      if (useXml)
      {
        if (!fileName.toLowerCase().endsWith(".xml"))
        {
          LOG.info("Ignored file: " + fileName);

          return;
        }

        try
        {
          JAXBContext  jaxbContext      = JAXBContext.newInstance(Page.class);
          Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();

          page = (Page) jaxbUnmarshaller.unmarshal(fileIn);
          LOG.info("Loaded web page " + page.getPageName() + " from  " + fileName);
        }
        catch (JAXBException e)
        {  // error out if we cannot marshall the XML into a page
          e.printStackTrace();

          return;
        }
      }
      else
      {
        if (!fileName.toLowerCase().endsWith(".json"))
        {
          return;
        }

        try
        {
          ObjectMapper objectMapper = new ObjectMapper();

          page = objectMapper.readValue(fileIn, Page.class);
        }
        catch (IOException e)
        {
          LOG.warn("Error parsing page [" + fileName + ']', e);

          return;
        }
      }

      // something is wrong with the page, let's ignore it.
      if (page == null)
      {
        LOG.warn("Failed to load web page [" + fileName + "] continue to load next.");

        return;
      }

      // put the pages into internal storage!
      pages.put(page.getPageName(), page);

      // add to domElementPool
      Map<String, DomElement> domElementsInPage = page.getDomElements();

      for (String componentIdentifier : domElementsInPage.keySet())
      {
        DomElement domElement = domElementsInPage.get(componentIdentifier);

        domElement.setIdentifier(componentIdentifier);

        // only add the first one in so that other page can override it.
        if (domElementPool.get(componentIdentifier) == null)
        {
          domElement.setPageInfo(page.getPageName());
          domElementPool.put(componentIdentifier, domElement);
        }
      }

      LOG.info("Successfully adding page entry of " + page.getPageName() + " with domElements: " + page.getDomElements());
    }
  }

  // building navigation graphs - future development
  private void buildNavigationGraph()
  {
    // always starts from the first page object.
    String           startingPathName        = settings.getProperty("startingPageName").trim();
    List<PageAction> pageActionsForFirstPage = pageActions.get(startingPathName);

    for (PageAction pageAction : pageActionsForFirstPage)
    {
      List<String> path = new ArrayList<>();

      // path.add(startingPathName);
      String pageName     = pageAction.getSourcePageName();
      String nextPageName = pageAction.getTargetPageName();
      String actionString = PageAction.serializeList(pageAction.getActionChains().get(0));
      String key          = pageName + ':' + actionString;

      path.add(key);
    }
  }

  private boolean shouldPathStop(List<String> aPath, String key)
  {
    for (String pageName : aPath)
    {
      if (pageName.equals(key))
      {
        return true;
      }
    }

    return false;
  }

  private ArrayList<String> clonePath(List<String> path)
  {
    ArrayList<String> newPath = new ArrayList<>();

    for (String pageName : path)
    {
      newPath.add(pageName);
    }

    return newPath;
  }

  // chrome driver
  public WebDriver getChromeDriver() throws MalformedURLException
  {
    DesiredCapabilities capabilities = DesiredCapabilities.chrome();

    capabilities.setCapability("chrome.switches", Arrays.asList(settings.getProperty("chrome.chrome.switches")));
    capabilities.setCapability(PLATFORM, applyPlatform());

    WebDriver driver;

    if (useRemoteWebDriver)
    {
      driver = new RemoteWebDriver(new URL(remoteWebDriverUrl), capabilities);
    }
    else
    {
      if (System.getProperty(WEBDRIVER_CHROME_DRIVER) == null)
      {
        String chromeDriver = Drivers.CHROME.getDriverName();

        if (StringUtils.isEmpty(chromeDriver))
        {
          throw new IllegalArgumentException("No chrome driver specified! Please specify as a system property or in your jazz.properties file.");
        }

        System.setProperty(WEBDRIVER_CHROME_DRIVER, chromeDriver);
      }

      driver = new ChromeDriver(capabilities);
    }

    return driver;
  }

  // jsheridan CODEREVIEW - note that if we used an enum, this logic could be moved into that, so the browser enum knew how to get it's driver
  // firefox
  public WebDriver getFirefoxDriver() throws MalformedURLException
  {
    WebDriver driver;

    if (useRemoteWebDriver)
    {
      DesiredCapabilities capabilities = DesiredCapabilities.firefox();

      capabilities.setCapability(PLATFORM, applyPlatform());
      capabilities.setCapability(VERSION, applyVersion());
      driver = new RemoteWebDriver(new URL(remoteWebDriverUrl), capabilities);
    }
    else
    {
      FirefoxProfile profile   = new FirefoxProfile();
      String         proxyType = settings.getProperty("firefox.network.proxy.type");

      if (proxyType.equalsIgnoreCase(ProxyType.AUTODETECT.name()))
      {
        profile.setPreference("network.proxy.type", ProxyType.AUTODETECT.ordinal());
      }
      else if (proxyType.equalsIgnoreCase(ProxyType.DIRECT.name()))
      {
        profile.setPreference("network.proxy.type", ProxyType.DIRECT.ordinal());
      }

      driver = new FirefoxDriver(profile);
      driver.manage().window().maximize();
    }

    return driver;
  }

  // ie driver
  public WebDriver getIEDriver() throws MalformedURLException
  {
    WebDriver driver;

    if (useRemoteWebDriver)
    {
      DesiredCapabilities capabilities = DesiredCapabilities.internetExplorer();

      capabilities.setCapability(PLATFORM, applyPlatform());
      capabilities.setCapability(VERSION, applyVersion());
      driver = new RemoteWebDriver(new URL(remoteWebDriverUrl), capabilities);
    }
    else
    {
      DesiredCapabilities capabilitiesInternet = new DesiredCapabilities();

      capabilitiesInternet.setCapability("ignoreProtectedModeSettings", settings.getProperty("ie.ignoreProtectedModeSettings"));
      capabilitiesInternet.setCapability("platform", applyPlatform());
      capabilitiesInternet.setCapability(CapabilityType.VERSION, applyVersion());
      driver = new InternetExplorerDriver(capabilitiesInternet);
    }

    driver.manage().window().maximize();

    return driver;
  }

  // safari
  public WebDriver getSafariDriver() throws MalformedURLException
  {
    if (useRemoteWebDriver)
    {
      DesiredCapabilities capabilities = DesiredCapabilities.safari();

      capabilities.setCapability(PLATFORM, applyPlatform());
      capabilities.setCapability(VERSION, applyVersion());

      return new RemoteWebDriver(new URL(remoteWebDriverUrl), capabilities);
    }
    else
    {
      return new SafariDriver();
    }
  }

  // safari
  public WebDriver getIPadDriver() throws MalformedURLException
  {
    if (useRemoteWebDriver)
    {
      DesiredCapabilities capabilities = DesiredCapabilities.ipad();

      capabilities.setCapability(PLATFORM, applyPlatform());
      capabilities.setCapability(VERSION, applyVersion());

      return new RemoteWebDriver(new URL(remoteWebDriverUrl), capabilities);
    }
    else
    {
      return new SafariDriver();
    }
  }

  public JavascriptExecutor getJQueryDriver(WebDriver webDriver)
  {
    return (JavascriptExecutor) webDriver;
  }

  public static void loadJQuery(JavascriptExecutor jsDriver)
  {
    Object jquery = jsDriver.executeScript(" if ( typeof $ != 'undefined') { return 1;} else { return null; }");

    if (jquery == null)
    {
      URL    jqueryUrl  = Resources.getResource("jquery-1.8.0.min.js");
      String jqueryText = "";

      try
      {
        jqueryText = Resources.toString(jqueryUrl, Charsets.UTF_8);
      }
      catch (IOException e)
      {
        LOG.warn("Error obtaining jquery library.", e);
      }

      LOG.info("\tEnable Jquery");
      jsDriver.executeScript(jqueryText);
    }
  }

  public String getCustomJS(String jsName)
  {
    URL    jsUrl  = Resources.getResource(jsName);
    String jsText = "";

    try
    {
      jsText = Resources.toString(jsUrl, Charsets.UTF_8);
    }
    catch (IOException e)
    {
      LOG.info("Error in javascript!", e);
    }

    return jsText;
  }

  private String applyPlatform()
  {
    if ((platform == null) || platform.trim().isEmpty())
    {
      return settings.getProperty(PLATFORM);
    }
    else
    {
      return platform;
    }
  }

  private String applyVersion()
  {
    if ((browserVersion == null) || browserVersion.trim().isEmpty())
    {
      return settings.getProperty(BROWSER_VERSION);
    }
    else
    {
      return browserVersion;
    }
  }

  public String getConfigurationsPath()
  {
    return configurationsPath;
  }

  public String getLogsPath()
  {
    return reportsPath;
  }

  public void setLogsPath(String logsPath)
  {
    reportsPath = logsPath;
  }

  public static String getRemoteWebDriverUrl()
  {
    return remoteWebDriverUrl;
  }

  public boolean isUseRemoteWebDriver()
  {
    return useRemoteWebDriver;
  }

  public void setRemoteWebDriverUrl(String remoteWebDriverUrl)
  {
    this.remoteWebDriverUrl = remoteWebDriverUrl;
  }

  public String getProjectName()
  {
    return projectName;
  }

  public void setProjectName(String projectName)
  {
    this.projectName = projectName;
  }

  public static int getPageLoadTimeout()
  {
    return pageLoadTimeout;
  }

  public static void setPageLoadTimeout(int pageLoadTimeout)
  {
    WebUIManager.pageLoadTimeout = pageLoadTimeout;
  }

  public static int getActionPace()
  {
    return actionPace;
  }

  public static void setActionPace(int actionPace)
  {
    WebUIManager.actionPace = actionPace;
  }

  public static String getFeatureNames()
  {
    return featureNames;
  }

  public static void setFeatureNames(String featureNames)
  {
    WebUIManager.featureNames = featureNames;
  }

  public void setUseRemoteWebDriver(boolean useRemoteWebDriver)
  {
    this.useRemoteWebDriver = useRemoteWebDriver;
  }

  public Browsers getBrowser()
  {
    return browser;
  }

  public void setBrowser(Browsers browser, boolean overwriteSystem)
  {
    // if system property is set, use that instead
    if (overwriteSystem)
    {
      this.browser = browser;
    }
    else
    {
      this.browser = getBrowserFromSystemProperty();
    }
  }

  public String getBrowserVersion()
  {
    return browserVersion;
  }

  public void setBrowserVersion(String browserVersion)
  {
    this.browserVersion = browserVersion;
  }

  public String getPlatform()
  {
    return platform;
  }

  public void setPlatform(String platform)
  {
    this.platform = platform;
  }

  public String getCustomClasspath()
  {
    return customClasspath;
  }

  public void setCustomClasspath(String customClasspath)
  {
    this.customClasspath = customClasspath;
  }

  public Map<String, Page> getPages()
  {
    return pages;
  }
}
