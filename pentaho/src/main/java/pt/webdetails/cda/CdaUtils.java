/*!
 * Copyright 2002 - 2018 Webdetails, a Hitachi Vantara company. All rights reserved.
 *
 * This software was developed by Webdetails and is provided under the terms
 * of the Mozilla Public License, Version 2.0, or any later version. You may not use
 * this file except in compliance with the license. If you need a copy of the license,
 * please go to  http://mozilla.org/MPL/2.0/. The Initial Developer is Webdetails.
 *
 * Software distributed under the Mozilla Public License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. Please refer to
 * the license for the specific language governing your rights and limitations.
 */
package pt.webdetails.cda;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_XML;
import static javax.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;
import static pt.webdetails.cda.CdaConstants.PREFIX_PARAMETER;
import static pt.webdetails.cda.CdaConstants.PREFIX_SETTING;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.QueryParam;
import javax.ws.rs.FormParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MultivaluedMap;

import com.google.common.annotations.VisibleForTesting;
import com.sun.jersey.core.util.MultivaluedMapImpl;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.api.engine.ILogger;
import org.pentaho.platform.api.engine.IParameterProvider;
import org.pentaho.platform.engine.core.solution.SimpleParameterProvider;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.apache.commons.lang.StringUtils;
import org.pentaho.platform.util.logging.SimpleLogger;
import pt.webdetails.cda.dataaccess.AbstractDataAccess;
import pt.webdetails.cda.dataaccess.DataAccessConnectionDescriptor;
import pt.webdetails.cda.exporter.ExportOptions;
import pt.webdetails.cda.exporter.ExportedQueryResult;
import pt.webdetails.cda.exporter.Exporter;
import pt.webdetails.cda.exporter.TableExporter;
import pt.webdetails.cda.exporter.ExporterException;
import pt.webdetails.cda.exporter.UnsupportedExporterException;
import pt.webdetails.cda.services.CacheManager;
import pt.webdetails.cda.services.Editor;
import pt.webdetails.cda.services.ExtEditor;
import pt.webdetails.cda.services.Previewer;

import org.pentaho.platform.api.engine.IPentahoSession;

import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.security.SecurityHelper;

import pt.webdetails.cda.settings.CdaSettingsReadException;
import pt.webdetails.cda.utils.CorsUtil;
import pt.webdetails.cda.utils.DoQueryParameters;
import pt.webdetails.cda.utils.Messages;
import pt.webdetails.cpf.PluginEnvironment;
import pt.webdetails.cpf.audit.CpfAuditHelper;
import pt.webdetails.cpf.messaging.JsonGeneratorSerializable;
import pt.webdetails.cpf.messaging.JsonResult;
import pt.webdetails.cpf.utils.CharsetHelper;
import pt.webdetails.cpf.utils.JsonHelper;
import pt.webdetails.cpf.utils.MimeTypes;

@Path( "/{plugin}/api" )
public class CdaUtils {
  private static final Log logger = LogFactory.getLog( CdaUtils.class );

  // TODO: safer to get from repos?
  private static final Pattern CDA_PATH = Pattern.compile( "^[^:]*([^/]+)[^?]*" );

  public CdaUtils() {
  }

  protected static String getEncoding() {
    return CharsetHelper.getEncoding();
  }

  // TODO: wildcard for exported types?
  @GET
  @Path( "/doQuery" )
  @Produces( { MimeTypes.JSON, MimeTypes.XML, MimeTypes.CSV, MimeTypes.XLS, MimeTypes.PLAIN_TEXT, MimeTypes.HTML } )
  public StreamingOutput doQueryGet( @Context UriInfo uriInfo,
                                     @Context HttpServletRequest servletRequest,
                                     @Context HttpServletResponse servletResponse ) throws WebApplicationException {
    setCorsHeaders( servletRequest, servletResponse );

    return doQuery( uriInfo.getQueryParameters(), servletResponse );
  }

  @POST
  @Path( "/doQuery" )
  @Consumes( APPLICATION_FORM_URLENCODED )
  @Produces( { MimeTypes.JSON, MimeTypes.XML, MimeTypes.CSV, MimeTypes.XLS, MimeTypes.PLAIN_TEXT, MimeTypes.HTML } )
  public StreamingOutput doQueryPost( MultivaluedMap<String, String> formParams,
                                      @Context HttpServletRequest servletRequest,
                                      @Context HttpServletResponse servletResponse ) throws WebApplicationException {
    setCorsHeaders( servletRequest, servletResponse );

    MultivaluedMap<String, String> params = formParams;
    if ( formParams.size() == 0 ) {
      // CDA-72: CAS http filters will clear out formParams - try to get data from the request parameter Map
      params = getParameterMapFromRequest( servletRequest );
    }

    return doQuery( params, servletResponse );
  }

  private ExportedQueryResult doQueryInternal( DoQueryParameters parameters ) throws Exception {
    CdaCoreService core = getCdaCoreService();

    return core.doQuery( parameters );
  }


  public StreamingOutput doQuery( MultivaluedMap<String, String> params,
                                  HttpServletResponse servletResponse ) throws WebApplicationException {
    final long start = System.currentTimeMillis();
    final ILogger auditLogger = getAuditLogger();
    final String path = params.get( "path" ).get( 0 );

    final UUID auditUuid = startAudit( path, auditLogger, params );
    try {
      final DoQueryParameters parameters = getDoQueryParameters( params );
      if ( parameters.isWrapItUp() ) {
        return wrapQuery( parameters );
      }

      final ExportedQueryResult result = doQueryInternal( parameters );
      result.writeHeaders( servletResponse );

      return toStreamingOutput( result );
    } catch ( Exception ex ) {
      throw new WebApplicationException( ex, 501 ); // TODO:
    } finally {
      endAudit( path, auditLogger, start, auditUuid );
    }
  }

  private UUID startAudit( String path, ILogger auditLogger, MultivaluedMap<String, String> params ) {
    final String pluginName = getPluginName();
    final String objectName = getObjectName();
    final IPentahoSession pentahoSession = getPentahoSession();
    final IParameterProvider requestParams = getParameterProvider( params );

    return CpfAuditHelper.startAudit( pluginName, path, objectName, pentahoSession, auditLogger, requestParams );
  }

  private void endAudit( String path, ILogger auditLogger, long start, UUID auditUuid ) {
    final long end = System.currentTimeMillis();

    final String pluginName = getPluginName();
    final String objectName = getObjectName();
    final IPentahoSession pentahoSession = getPentahoSession();

    CpfAuditHelper.endAudit( pluginName, path, objectName, pentahoSession, auditLogger, start, auditUuid, end );
  }

  private String getObjectName() {
    return CdaUtils.class.getName();
  }

  private String getPluginName() {
    return CdaPluginEnvironment.getInstance().getPluginId();
  }

  private ILogger getAuditLogger() {
    return new SimpleLogger( CdaUtils.class.getName() );
  }

  private IParameterProvider getParameterProvider( MultivaluedMap<String, String> params ) {
    return new SimpleParameterProvider( params );
  }

  private StreamingOutput wrapQuery( DoQueryParameters parameters ) throws Exception {
    final String uuid = getCdaCoreService().wrapQuery( parameters );

    return out -> IOUtils.write( uuid, out );
  }

  private DoQueryParameters getDoQueryParameters( MultivaluedMap<String, String> parameters ) throws Exception {
    DoQueryParameters doQueryParams = new DoQueryParameters();

    // should populate everything but prefixed parameters TODO: recheck defaults
    BeanUtils.populate( doQueryParams, parameters );

    Map<String, Object> params = new HashMap<>();
    Map<String, Object> extraSettings = new HashMap<>();

    for ( String name : parameters.keySet() ) {
      final Object value = getParam( parameters.get( name ) );

      if ( name.startsWith( PREFIX_PARAMETER ) ) {
        final String extraParameterName = name.replaceFirst( PREFIX_PARAMETER, "" );

        params.put( extraParameterName, value );
      } else if ( name.startsWith( PREFIX_SETTING ) ) {
        final String extraSettingName = name.replaceFirst( PREFIX_SETTING, "" );

        extraSettings.put( extraSettingName, value );
      }
    }

    doQueryParams.setParameters( params );
    doQueryParams.setExtraSettings( extraSettings );

    return doQueryParams;
  }

  private Object getParam( List<String> paramValues ) {
    if ( paramValues == null ) {
      return null;
    }

    if ( paramValues.size() == 1 ) {
      return paramValues.get( 0 );
    }

    return paramValues.toArray();
  }

  @GET
  @Path( "/unwrapQuery" )
  @Produces()
  public void unwrapQuery( @QueryParam( "path" ) String path,
                           @QueryParam( "uuid" ) String uuid,
                           @Context HttpServletResponse servletResponse,
                           @Context HttpServletRequest servletRequest ) throws WebApplicationException {
    try {
      ExportedQueryResult result = getCdaCoreService().unwrapQuery( path, uuid );

      setCorsHeaders( servletRequest, servletResponse );
      result.writeResponse( servletResponse );
    } catch ( Exception e ) {
      logger.error( e );
      throw new WebApplicationException( e, Response.Status.INTERNAL_SERVER_ERROR );
    }
  }

  @GET
  @Path( "/listQueries" )
  @Produces( { MimeTypes.JSON, MimeTypes.XML, MimeTypes.CSV, MimeTypes.XLS, MimeTypes.PLAIN_TEXT, MimeTypes.HTML } )
  public StreamingOutput listQueries( @QueryParam( "path" ) String path,
                                      @DefaultValue( "json" ) @QueryParam( "outputType" ) String outputType,
                                      @Context HttpServletResponse servletResponse ) throws WebApplicationException {
    if ( StringUtils.isEmpty( path ) ) {
      throw new IllegalArgumentException( "No path provided" );
    }

    logger.debug( "Do Query: getSolPath:" + PentahoSystem.getApplicationContext().getSolutionPath( path ) );

    ExportedQueryResult result;
    try {
      result = getCdaCoreService().listQueries( path, getSimpleExportOptions( outputType ) );
    } catch ( Exception e ) {
      logger.error( e );
      throw new WebApplicationException( e );
    }

    return toStreamingOutput( result );
  }

  private StreamingOutput toStreamingOutput( final ExportedQueryResult result ) {
    return out -> {
      try {
        result.writeOut( out );
      } catch ( ExporterException e ) {
        throw new WebApplicationException( e );
      }
    };
  }

  private StreamingOutput toStreamingOutput( final JsonGeneratorSerializable json ) {
    return out -> JsonHelper.writeJson( json, out );
  }

  private StreamingOutput toErrorResult( final Exception ex ) {
    logger.error( ex.getLocalizedMessage(), ex );

    return out -> JsonHelper.writeJson( new JsonResult( false, ex.getLocalizedMessage() ), out );
  }

  private ExportOptions getSimpleExportOptions( final String outputType ) {
    return getSimpleExportOptions( outputType,  Collections.emptyMap() );
  }

  private ExportOptions getSimpleExportOptions( final String outputType, final Map<String, String> extraSettings ) {
    return new ExportOptions() {

      public String getOutputType() {
        return outputType;
      }

      public Map<String, String> getExtraSettings() {
        return extraSettings;
      }
    };
  }

  @GET
  @Path( "/listParameters" )
  @Produces( { MimeTypes.JSON, MimeTypes.XML, MimeTypes.CSV, MimeTypes.XLS, MimeTypes.PLAIN_TEXT, MimeTypes.HTML } )
  public StreamingOutput listParameters( @QueryParam( "path" ) String path,
                                         @QueryParam( "dataAccessId" ) String dataAccessId,
                                         @DefaultValue( "json" ) @QueryParam( "outputType" ) String outputType )
    throws WebApplicationException {
    logger.debug( "Do Query: getSolPath:" + path );

    try {
      final ExportOptions exportOptions = getSimpleExportOptions( outputType );
      final ExportedQueryResult result = getCdaCoreService().listParameters( path, dataAccessId, exportOptions );

      return toStreamingOutput( result );
    } catch ( Exception e ) {
      logger.error( e );
      throw new WebApplicationException( e );
    }
  }

  private TableExporter useExporter( final CdaEngine engine, final String outputType,
                                     HttpServletResponse servletResponse ) throws UnsupportedExporterException {
    final ExportOptions exportOptions = getSimpleExportOptions( outputType, null );

    return useExporter( engine, exportOptions, servletResponse );
  }

  private TableExporter useExporter( final CdaEngine engine, ExportOptions exportOptions,
                                     HttpServletResponse servletResponse ) throws UnsupportedExporterException {
    // Handle the query itself and its output format...
    Exporter exporter = engine.getExporter( exportOptions );

    String mimeType = exporter.getMimeType();
    if ( mimeType != null ) {
      servletResponse.setHeader( "Content-Type", mimeType );
    }

    String attachmentName = exporter.getAttachmentName();
    if ( attachmentName != null ) {
      servletResponse.setHeader( "content-disposition", "attachment; filename=" + attachmentName );
    }
    return (TableExporter) exporter;
  }

  @GET
  @Path( "/getCdaFile" )
  @Produces( MimeTypes.JSON )
  public StreamingOutput getCdaFile( @QueryParam( "path" ) String path ) throws WebApplicationException {
    String filePath = StringUtils.replace( path, "///", "/" );

    JsonGeneratorSerializable json;
    try {
      String document = getEditor().getFile( filePath );
      if ( document != null ) {
        json = new JsonResult( true, document );
      } else {
        json = new JsonResult( false, "Unable to read file." );
      }
    } catch ( Exception e ) {
      return toErrorResult( e );
    }

    return toStreamingOutput( json );
  }

  @GET
  @Path( "/canEdit" )
  @Produces( MimeTypes.JSON )
  public StreamingOutput canEdit( @QueryParam( "path" ) String path ) {
    boolean canEdit = getEditor().canEdit( path );
    return toStreamingOutput( new JsonResult( true, JsonHelper.toJson( canEdit ) ) );
  }

  private Editor getEditor() {
    return new Editor();
  }

  @POST
  @Path( "/writeCdaFile" )
  @Produces( MimeTypes.JSON )
  public StreamingOutput writeCdaFile( @FormParam( "path" ) String path,
                                       @FormParam( "data" ) String data ) {
    // TODO: Validate the filename in some way, shape or form!
    if ( data == null ) {
      logger.error( "writeCdaFile: no data to save provided " + path );
      return toStreamingOutput( new JsonResult( false, "No Data!" ) );
    }

    try {
      return toStreamingOutput( new JsonResult( getEditor().writeFile( path, data ), path ) );
    } catch ( Exception e ) {
      return toErrorResult( e );
    }
  }

  @GET
  @Path( "/getCdaList" )
  @Consumes( { APPLICATION_XML, APPLICATION_JSON } )
  public void getCdaList( @DefaultValue( "json" ) @QueryParam( "outputType" ) String outputType,
                          @Context HttpServletResponse servletResponse,
                          @Context HttpServletRequest servletRequest ) throws Exception {
    final CdaEngine engine = CdaEngine.getInstance();

    TableExporter exporter = useExporter( engine, outputType, servletResponse );
    exporter.export( servletResponse.getOutputStream(), engine.getCdaList() );
    servletResponse.getOutputStream().flush();
  }

  @GET
  @Path( "/clearCache" )
  @Produces( "text/plain" )
  @Consumes( { APPLICATION_XML, APPLICATION_JSON } )
  public String clearCache( @Context HttpServletResponse servletResponse,
                            @Context HttpServletRequest servletRequest ) throws Exception {
    String msg = "Cache Cleared Successfully";

    // Check if user is admin
    Boolean accessible = SecurityHelper.getInstance().isPentahoAdministrator( getPentahoSession() );
    if ( !accessible ) {
      msg = "Method clearCache not exposed or user does not have required permissions.";

      logger.error( msg );
      servletResponse.sendError( HttpServletResponse.SC_FORBIDDEN, msg );
      return msg;
    }

    try {
      CdaEngine.getInstance().getSettingsManager().clearCache();
      AbstractDataAccess.clearCache();
    } catch ( Exception cce ) {
      msg = "Method clearCache failed while trying to execute.";

      logger.error( msg, cce );
      servletResponse.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR, msg );
    }

    return msg;
  }

  @GET
  @Path( "/editFile" )
  @Produces( MimeTypes.HTML )
  public String editFile( @QueryParam( "path" ) String path ) throws WebApplicationException, IOException {
    if ( StringUtils.isEmpty( path ) ) {
      throw new WebApplicationException( 400 );
    }

    if ( !CdaEngine.getEnvironment().canCreateContent() ) {
      return Messages.getString( "CdaUtils.ERROR_ACCESS_DENIED" );
    }

    return getExtEditor().getMainEditor();
  }

  @GET
  @Path( "/extEditor" )
  @Produces( MimeTypes.HTML )
  public String getExtEditor( @QueryParam( "path" ) String path ) throws WebApplicationException, IOException {
    if ( StringUtils.isEmpty( path ) ) {
      throw new WebApplicationException( 400 );
    }

    return getExtEditor().getExtEditor();
  }

  /**
   * called by content generator
   */
  public void editFile( String path, @Context HttpServletResponse servletResponse ) throws IOException {
    final String editFileUrl = PluginEnvironment.env().getUrlProvider().getPluginBaseUrl() + "editFile?path=" + path;

    servletResponse.sendRedirect( editFileUrl );
  }

  public void previewQuery( String path, @Context HttpServletResponse servletResponse ) throws IOException {
    final String previewQueryUrl = PluginEnvironment.env().getUrlProvider()
            .getPluginBaseUrl() + "previewQuery?path=" + path;

    servletResponse.sendRedirect( previewQueryUrl );
  }

  private CacheManager getCacheManager() {
    return new CacheManager( PluginEnvironment.env().getUrlProvider(), CdaEngine.getEnvironment().getRepo() );
  }

  @GET
  @Path( "/previewQuery" )
  @Produces( MimeTypes.HTML )
  public String previewQuery( @Context HttpServletRequest servletRequest ) throws Exception {
    String path = getPath( servletRequest );

    try {
      checkFileExists( path );
    } catch ( Exception e ) {
      logger.error( "Error on trying to read: " + path, e );
      throw e;
    }

    return getPreviewer().previewQuery( path );
  }

  @VisibleForTesting
  void checkFileExists( String path ) throws CdaSettingsReadException, AccessDeniedException {
    CdaEngine.getInstance().getSettingsManager().parseSettingsFile( path );
  }

  private String getPath( HttpServletRequest servletRequest ) {
    String path = servletRequest.getParameter( "path" );
    if ( !StringUtils.isEmpty( path ) ) {
      return path;
    }

    String uri = servletRequest.getRequestURI();
    Matcher pathFinder = CDA_PATH.matcher( uri );
    if ( pathFinder.lookingAt() ) {
      path = pathFinder.group( 1 );

      return path.replaceAll( ":", "/" );
    }

    return null;
  }

  @VisibleForTesting
  Previewer getPreviewer() {
    return new Previewer( PluginEnvironment.env().getUrlProvider(), CdaEngine.getEnvironment().getRepo() );
  }

  private ExtEditor getExtEditor() {
    return new ExtEditor( PluginEnvironment.env().getUrlProvider(), CdaEngine.getEnvironment().getRepo() );
  }

  /**
   * For CDE discovery
   */
  @GET
  @Path( "/listDataAccessTypes" )
  @Produces( APPLICATION_JSON )
  @Consumes( { APPLICATION_XML, APPLICATION_JSON } )
  public String listDataAccessTypes( @DefaultValue( "false" ) @QueryParam( "refreshCache" ) Boolean refreshCache ) {
    DataAccessConnectionDescriptor[] data =
      CdaEngine.getInstance().getSettingsManager().getDataAccessDescriptors( refreshCache );

    StringBuilder output = new StringBuilder();
    if ( data != null ) {
      output.append( "{\n" );

      for ( DataAccessConnectionDescriptor datum : data ) {
        output.append( datum.toJSON() ).append( ",\n" );
      }

      return output.toString().replaceAll( ",\n\\z", "\n}" );
    } else {
      return ""; // XXX
    }
  }

  @GET
  @Path( "/manageCache" )
  @Produces( MimeTypes.HTML )
  public String manageCache() throws Exception {
    return getCacheManager().manageCache();
  }

  private IPentahoSession getPentahoSession() {
    return PentahoSessionHolder.getSession();
  }

  private CdaCoreService getCdaCoreService() {
    return new CdaCoreService( CdaEngine.getInstance() );
  }

  // Interplugin calls  - Should be moved to a dedicated bean and method signature should be changed
  // Compatibility with CDF 5-Trunk
  @Deprecated
  public void listQueriesInterPluginOld( @QueryParam( "path" ) String path,
                                         @DefaultValue( "json" ) @QueryParam( "outputType" ) String outputType,
                                         @Context HttpServletResponse servletResponse,
                                         @Context HttpServletRequest servletRequest )
    throws WebApplicationException, IOException {
    StreamingOutput so = listQueries( path, outputType, servletResponse );

    so.write( servletResponse.getOutputStream() );
  }

  @Deprecated
  public void doQueryInterPluginOld( @Context HttpServletResponse servletResponse,
                                     @Context HttpServletRequest servletRequest ) throws Exception {
    MultivaluedMap<String, String> params = getParameterMapFromRequest( servletRequest );
    ExportedQueryResult result = doQueryInternal( getDoQueryParameters( params ) );
    result.writeResponse( servletResponse );
  }

  public String doQueryInterPlugin( @Context HttpServletRequest servletRequest ) throws Exception {
    return doQueryInternal( getDoQueryParameters( getParameterMapFromRequest( servletRequest ) ) ).asString();
  }

  private MultivaluedMap<String, String> getParameterMapFromRequest( HttpServletRequest servletRequest ) {
    MultivaluedMap<String, String> params = new MultivaluedMapImpl();

    final Enumeration enumeration = servletRequest.getParameterNames();
    while ( enumeration.hasMoreElements() ) {
      final String param = (String) enumeration.nextElement();

      final String[] values = servletRequest.getParameterValues( param );
      if ( values.length == 1 ) {
        params.add( param, values[ 0 ] );
      } else {
        List<String> list = new ArrayList<>();
        Collections.addAll( list, values );

        params.put( param, list ); // assigns the array
      }
    }

    return params;
  }

  private void setCorsHeaders( HttpServletRequest request, HttpServletResponse response ) {
    CorsUtil.getInstance().setCorsHeaders( request, response );
  }

  // TODO: this comment belongs to which method?
  // Adding this because of compatibility with the reporting plugin on 5.0.1. The cda datasource on the reporting plugin
  // is expecting this signature

  @Deprecated
  public void listParameters( @QueryParam( "path" ) String path,
                              @QueryParam( "solution" ) String solution,
                              @QueryParam( "file" ) String file,
                              @DefaultValue( "json" ) @QueryParam( "outputType" ) String outputType,
                              @DefaultValue( "<blank>" ) @QueryParam( "dataAccessId" ) String dataAccessId,
                              @Context HttpServletResponse servletResponse,
                              @Context HttpServletRequest servletRequest ) {


    logger.debug( "Do Query: getSolPath:" + path );
    try {
      final ExportOptions exportOptions = getSimpleExportOptions( outputType );

      ExportedQueryResult result = getCdaCoreService().listParameters( path, dataAccessId, exportOptions );
      result.writeOut( servletResponse.getOutputStream() );
    } catch ( Exception e ) {
      logger.error( e );
      throw new WebApplicationException( e );
    }
  }


  @Deprecated
  public void doQueryPost( @FormParam( "path" ) String path,
                           @DefaultValue( "json" ) @FormParam( "outputType" ) String outputType,
                           @DefaultValue( "1" ) @FormParam( "outputIndexId" ) int outputIndexId,
                           @DefaultValue( "1" ) @FormParam( "dataAccessId" ) String dataAccessId,
                           @DefaultValue( "false" ) @FormParam( "bypassCache" ) Boolean bypassCache,
                           @DefaultValue( "false" ) @FormParam( "paginateQuery" ) Boolean paginateQuery,
                           @DefaultValue( "0" ) @FormParam( "pageSize" ) int pageSize,
                           @DefaultValue( "0" ) @FormParam( "pageStart" ) int pageStart,
                           @DefaultValue( "false" ) @FormParam( "wrapItUp" ) Boolean wrapItUp,
                           @FormParam( "sortBy" ) List<String> sortBy,
                           @Context HttpServletResponse servletResponse,
                           @Context HttpServletRequest servletRequest ) throws Exception {

    DoQueryParameters parameters = new DoQueryParameters( path );
    parameters.setOutputType( outputType );
    parameters.setOutputIndexId( outputIndexId );
    parameters.setDataAccessId( dataAccessId );
    parameters.setBypassCache( bypassCache );
    parameters.setPageSize( pageSize );
    parameters.setPageStart( pageStart );
    parameters.setWrapItUp( wrapItUp );
    parameters.setSortBy( sortBy );

    ExportedQueryResult result = doQueryInternal( parameters );
    result.writeResponse( servletResponse );
  }
}
