package org.pentaho.platform.dataaccess.datasource.wizard.service.impl;

import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.pentaho.agilebi.modeler.ModelerWorkspace;
import org.pentaho.agilebi.modeler.gwt.GwtModelerWorkspaceHelper;
import org.pentaho.agilebi.modeler.util.TableModelerSource;
import org.pentaho.database.model.IDatabaseConnection;
import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.core.Props;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.metadata.model.Domain;
import org.pentaho.metadata.model.olap.OlapDimension;
import org.pentaho.metadata.repository.IMetadataDomainRepository;
import org.pentaho.platform.api.data.IDBDatasourceService;
import org.pentaho.platform.api.engine.IAuthorizationPolicy;
import org.pentaho.platform.api.engine.IPentahoDefinableObjectFactory;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.engine.IPluginResourceLoader;
import org.pentaho.platform.api.engine.ISecurityHelper;
import org.pentaho.platform.api.engine.ISolutionEngine;
import org.pentaho.platform.api.engine.IUserRoleListService;
import org.pentaho.platform.api.repository.IClientRepositoryPathsStrategy;
import org.pentaho.platform.api.repository.datasource.DatasourceMgmtServiceException;
import org.pentaho.platform.api.repository.datasource.DuplicateDatasourceException;
import org.pentaho.platform.api.repository.datasource.IDatasourceMgmtService;
import org.pentaho.platform.api.repository.datasource.NonExistingDatasourceException;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.engine.core.system.SystemSettings;
import org.pentaho.platform.engine.core.system.boot.PlatformInitializationException;
import org.pentaho.platform.engine.services.connection.datasource.dbcp.JndiDatasourceService;
import org.pentaho.platform.engine.services.solution.SolutionEngine;
import org.pentaho.platform.plugin.action.mondrian.catalog.IMondrianCatalogService;
import org.pentaho.platform.plugin.action.mondrian.catalog.MondrianCatalogHelper;
import org.pentaho.platform.plugin.action.mondrian.mapper.MondrianOneToOneUserRoleListMapper;
import org.pentaho.platform.plugin.services.connections.mondrian.MDXConnection;
import org.pentaho.platform.plugin.services.connections.mondrian.MDXOlap4jConnection;
import org.pentaho.platform.plugin.services.connections.sql.SQLConnection;
import org.pentaho.platform.plugin.services.importexport.ImportException;
import org.pentaho.platform.plugin.services.importexport.ImportSource;
import org.pentaho.platform.plugin.services.importexport.MetadataImportHandler;
import org.pentaho.platform.plugin.services.importexport.MondrianImportHandler;
import org.pentaho.platform.plugin.services.importexport.RepositoryFileBundle;
import org.pentaho.platform.plugin.services.metadata.PentahoMetadataDomainRepository;
import org.pentaho.platform.plugin.services.pluginmgr.PluginClassLoader;
import org.pentaho.platform.plugin.services.pluginmgr.PluginResourceLoader;
import org.pentaho.platform.repository2.unified.RepositoryUtils;
import org.pentaho.platform.repository2.unified.fs.FileSystemBackedUnifiedRepository;
import org.pentaho.test.platform.engine.core.MicroPlatform;
import org.pentaho.test.platform.engine.security.MockSecurityHelper;
import org.springframework.dao.DataAccessException;
import org.springframework.security.GrantedAuthority;
import org.springframework.security.GrantedAuthorityImpl;
import org.springframework.security.context.SecurityContextHolder;
import org.springframework.security.userdetails.User;
import org.springframework.security.userdetails.UserDetails;
import org.springframework.security.userdetails.UserDetailsService;
import org.springframework.security.userdetails.UsernameNotFoundException;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DatasourceResourceTest {
  private static MicroPlatform mp;

  @BeforeClass
  public static void setUp() throws Exception {
    System.setProperty( "org.osjava.sj.root", "test-res/solution1/system/simple-jndi" ); //$NON-NLS-1$ //$NON-NLS-2$
    mp = new MicroPlatform( "test-res/solution1" );

    IAuthorizationPolicy mockAuthorizationPolicy = mock( IAuthorizationPolicy.class );
    when( mockAuthorizationPolicy.isAllowed( anyString() ) ).thenReturn( true );

    IUserRoleListService mockUserRoleListService = mock( IUserRoleListService.class );

    mp.define( ISolutionEngine.class, SolutionEngine.class, IPentahoDefinableObjectFactory.Scope.GLOBAL );
    mp.define( IUnifiedRepository.class, TestFileSystemBackedUnifiedRepository.class, IPentahoDefinableObjectFactory.Scope.GLOBAL );
    mp.define( IMondrianCatalogService.class, MondrianCatalogHelper.class, IPentahoDefinableObjectFactory.Scope.GLOBAL );
    mp.define( "connection-SQL", SQLConnection.class );
    mp.define( "connection-MDX", MDXConnection.class );
    mp.define( "connection-MDXOlap4j", MDXOlap4jConnection.class );
    mp.define( IDBDatasourceService.class, JndiDatasourceService.class, IPentahoDefinableObjectFactory.Scope.GLOBAL );
    mp.define( MDXConnection.MDX_CONNECTION_MAPPER_KEY, MondrianOneToOneUserRoleListMapper.class, IPentahoDefinableObjectFactory.Scope.GLOBAL );
    mp.define( IDatasourceMgmtService.class, MockDatasourceMgmtService.class );
    mp.define( IClientRepositoryPathsStrategy.class, MockClientRepositoryPathsStrategy.class );
    mp.define( ISecurityHelper.class, MockSecurityHelper.class );
    mp.define( UserDetailsService.class, MockUserDetailService.class );
    mp.define( "singleTenantAdminUserName", "admin" );
    mp.defineInstance( IMetadataDomainRepository.class, createMetadataDomainRepository() );
    mp.defineInstance( IAuthorizationPolicy.class, mockAuthorizationPolicy );
    mp.defineInstance( IPluginResourceLoader.class, new PluginResourceLoader() {
      protected PluginClassLoader getOverrideClassloader() {
        return new PluginClassLoader( new File( ".", "test-res/solution1/system/simple-jndi" ), this );
      }
    } );
    mp.defineInstance( IUserRoleListService.class, mockUserRoleListService );

    mp.setSettingsProvider( new SystemSettings() );
    mp.start();

    PentahoSessionHolder.setStrategyName( PentahoSessionHolder.MODE_GLOBAL );
    SecurityContextHolder.setStrategyName( SecurityContextHolder.MODE_GLOBAL );
  }

  @Before
  @After
  public void clearDSWData() {
    File repoData = new File( "test-res/dsw/etc" );
    if ( repoData.exists() && repoData.isDirectory() ) {
      clearDir( repoData );
    }
  }

  private void clearDir( File dir ) {
    if ( dir.isDirectory() ) {
      for ( File file : dir.listFiles() ) {
        if ( file.isDirectory() ) {
          clearDir( file );
        } else {
          file.delete();
        }
      }
    }
  }

  @Test
  public void test() throws Exception {
    final String domainName = "SalesData";
    System.setProperty( "org.osjava.sj.root", "test-res/solution1/system/simple-jndi" ); //$NON-NLS-1$ //$NON-NLS-2$

    final ArrayList<ImportSource.IRepositoryFileBundle> importFileSet = new ArrayList<ImportSource.IRepositoryFileBundle>();

    File mondrian = new File( "test-res/dsw/testData/SalesData.mondrian.xml" );
    RepositoryFile repoMondrianFile = new RepositoryFile.Builder( mondrian.getName() ).folder( false ).hidden( false ).build();
    importFileSet.add( new RepositoryFileBundle( repoMondrianFile, null, "SalesData/", mondrian, "UTF-8", "text/xml" ) );

    File ds = new File( "test-res/dsw/testData/datasources.xml" );
    RepositoryFile repoDSFile = new RepositoryFile.Builder( ds.getName() ).folder( false ).hidden( false ).build();
    importFileSet.add( new RepositoryFileBundle( repoDSFile, null, "SalesData/", ds, "UTF-8", "text/xml" ) );

    new MondrianImportHandler( PentahoSystem.get( IUnifiedRepository.class ) ).doImport( importFileSet, "/SalesData", "", true );

    try {
      KettleEnvironment.init();
      Props.init( Props.TYPE_PROPERTIES_EMPTY );
    } catch ( Exception e ) {
      // may already be initialized by another test
    }

    Domain domain = generateModel();

    ModelerWorkspace model = new ModelerWorkspace( new GwtModelerWorkspaceHelper() );
    model.setModelName( "ORDERS" );
    model.setDomain( domain );
    model.getWorkspaceHelper().populateDomain( model );

    new ModelerService().serializeModels( domain, domainName );

    final Response salesData = new DatasourceResource().doGetDSWFilesAsDownload( domainName + ".xmi" );
    Assert.assertEquals( salesData.getStatus(), Response.Status.OK.getStatusCode() );
    Assert.assertNotNull( salesData.getMetadata() );
    Assert.assertNotNull( salesData.getMetadata().getFirst( "Content-Disposition" ) );
    Assert.assertEquals( salesData.getMetadata().getFirst( "Content-Disposition" ).getClass(), String.class );
    Assert.assertTrue( ( (String) salesData.getMetadata().getFirst( "Content-Disposition" ) ).endsWith( domainName + ".zip\"" ) );

    File file = File.createTempFile( domainName, ".zip" );
    final FileOutputStream fileOutputStream = new FileOutputStream( file );
    ( (StreamingOutput) salesData.getEntity() ).write( fileOutputStream );
    fileOutputStream.close();

    final ZipFile zipFile = new ZipFile( file );
    final Enumeration<? extends ZipEntry> entries = zipFile.entries();
    while ( entries.hasMoreElements() ) {
      final ZipEntry zipEntry = entries.nextElement();
      Assert.assertTrue( zipEntry.getName().equals( domainName + ".xmi" ) || zipEntry.getName().equals( domainName + ".mondrian.xml" ) );
    }
    zipFile.close();
    file.delete();
  }

  @Test
  public void testMetadataExport() throws PlatformInitializationException, ImportException, IOException {
    final ArrayList<ImportSource.IRepositoryFileBundle> importFileSet = new ArrayList<ImportSource.IRepositoryFileBundle>();

    File metadata = new File( "test-res/dsw/testData/metadata.xmi" );
    RepositoryFile repoMetadataFile = new RepositoryFile.Builder( metadata.getName() ).folder( false ).hidden( false ).build();
    importFileSet.add( new RepositoryFileBundle( repoMetadataFile, null, "SalesData/", metadata, "UTF-8", "text/xml" ) );

    new MetadataImportHandler( PentahoSystem.get( IUnifiedRepository.class ) ).doImport( importFileSet, "/SalesData", "", true );

    final Response salesData = new DatasourceResource().doGetDSWFilesAsDownload( "SalesData" );
    Assert.assertEquals( salesData.getStatus(), Response.Status.OK.getStatusCode() );
    Assert.assertNotNull( salesData.getMetadata() );
    Assert.assertNotNull( salesData.getMetadata().getFirst( "Content-Disposition" ) );
    Assert.assertEquals( salesData.getMetadata().getFirst( "Content-Disposition" ).getClass(), String.class );
    Assert.assertTrue( ( (String) salesData.getMetadata().getFirst( "Content-Disposition" ) ).endsWith( ".xmi\"" ) );
  }

  private static PentahoMetadataDomainRepository createMetadataDomainRepository() throws Exception {
    IUnifiedRepository repository = new FileSystemBackedUnifiedRepository( "test-res/dsw" );
    mp.defineInstance( IUnifiedRepository.class, repository );
    Assert.assertNotNull( new RepositoryUtils( repository ).getFolder( "/etc/metadata", true, true, null ) );
    Assert.assertNotNull( new RepositoryUtils( repository ).getFolder( "/etc/mondrian", true, true, null ) );
    PentahoMetadataDomainRepository pentahoMetadataDomainRepository = new PentahoMetadataDomainRepository( repository );
    return pentahoMetadataDomainRepository;
  }

  private Domain generateModel() {
    Domain domain = null;
    try {

      DatabaseMeta database = new DatabaseMeta();
      database.setDatabaseType( "Hypersonic" ); //$NON-NLS-1$
      database.setAccessType( DatabaseMeta.TYPE_ACCESS_JNDI );
      database.setDBName( "SampleData" ); //$NON-NLS-1$
      database.setName( "SampleData" ); //$NON-NLS-1$

      System.out.println( database.testConnection() );

      TableModelerSource source = new TableModelerSource( database, "ORDERS", null ); //$NON-NLS-1$
      domain = source.generateDomain();

      List<OlapDimension> olapDimensions = new ArrayList<OlapDimension>();
      OlapDimension dimension = new OlapDimension();
      dimension.setName( "test" ); //$NON-NLS-1$
      dimension.setTimeDimension( false );
      olapDimensions.add( dimension );
      domain.getLogicalModels().get( 1 ).setProperty( "olap_dimensions", olapDimensions ); //$NON-NLS-1$

    } catch ( Exception e ) {
      e.printStackTrace();
    }
    return domain;
  }

  public static class MockDatasourceMgmtService implements IDatasourceMgmtService {

    @Override
    public void init( IPentahoSession arg0 ) {
    }

    @Override
    public String createDatasource( IDatabaseConnection arg0 ) throws DuplicateDatasourceException,
        DatasourceMgmtServiceException {
      return null;
    }

    @Override
    public void deleteDatasourceById( String arg0 ) throws NonExistingDatasourceException, DatasourceMgmtServiceException {

    }

    @Override
    public void deleteDatasourceByName( String arg0 ) throws NonExistingDatasourceException,
        DatasourceMgmtServiceException {

    }

    @Override
    public IDatabaseConnection getDatasourceById( String arg0 ) throws DatasourceMgmtServiceException {
      return null;
    }

    @Override
    public IDatabaseConnection getDatasourceByName( String arg0 ) throws DatasourceMgmtServiceException {
      return null;
    }

    @Override
    public List<String> getDatasourceIds() throws DatasourceMgmtServiceException {
      return null;
    }

    @Override
    public List<IDatabaseConnection> getDatasources() throws DatasourceMgmtServiceException {
      return null;
    }

    @Override
    public String updateDatasourceById( String arg0, IDatabaseConnection arg1 ) throws NonExistingDatasourceException,
        DatasourceMgmtServiceException {
      return null;
    }

    @Override
    public String updateDatasourceByName( String arg0, IDatabaseConnection arg1 ) throws NonExistingDatasourceException,
        DatasourceMgmtServiceException {
      return null;
    }
  }

  public static class MockUserDetailService implements UserDetailsService {

    @Override
    public UserDetails loadUserByUsername( String name ) throws UsernameNotFoundException, DataAccessException {

      GrantedAuthority[] auths = new GrantedAuthority[2];
      auths[0] = new GrantedAuthorityImpl( "Authenticated" );
      auths[1] = new GrantedAuthorityImpl( "Administrator" );

      UserDetails user = new User( name, "password", true, true, true, true, auths );

      return user;
    }

  }

  public static class TestFileSystemBackedUnifiedRepository extends FileSystemBackedUnifiedRepository {
    public TestFileSystemBackedUnifiedRepository() {
      super( "bin/test-solutions/solution" );
    }
  }

  public static class MockClientRepositoryPathsStrategy implements IClientRepositoryPathsStrategy {

    @Override
    public String getEtcFolderName() {

      return null;
    }

    @Override
    public String getEtcFolderPath() {

      return null;
    }

    @Override
    public String getHomeFolderName() {

      return null;
    }

    @Override
    public String getHomeFolderPath() {

      return null;
    }

    @Override
    public String getPublicFolderName() {

      return null;
    }

    @Override
    public String getPublicFolderPath() {

      return null;
    }

    @Override
    public String getRootFolderPath() {

      return null;
    }

    @Override
    public String getUserHomeFolderName( String arg0 ) {

      return null;
    }

    @Override
    public String getUserHomeFolderPath( String arg0 ) {

      return null;
    }

  }
}
