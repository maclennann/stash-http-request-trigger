package com.zerosumtech.wparad.stash;

import java.io.DataOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atlassian.bitbucket.hook.repository.RepositoryHook;
import com.atlassian.bitbucket.hook.repository.RepositoryHookService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.permission.PermissionValidationService;
import com.atlassian.bitbucket.user.SecurityService;
import com.atlassian.bitbucket.util.Operation;

public class RepositoryInformationService 
{
	private static final Logger logger = LoggerFactory.getLogger(RepositoryInformationService.class);
	
	private final PermissionValidationService permissionValidationService;
	private final RepositoryHookService repositoryHookService;
	private final SecurityService securityService;
	  
	public RepositoryInformationService(
			PermissionValidationService permissionValidationService, 
			RepositoryHookService repositoryHookService,
			SecurityService securityService) 
	{
		this.permissionValidationService = permissionValidationService;
	  	this.repositoryHookService = repositoryHookService;
		this.securityService = securityService;
	}
	  
	public boolean IsPluginEnabled(final Repository repository)
	{
		permissionValidationService.validateForRepository(repository, Permission.REPO_READ);
		try 
		{
			return securityService.withPermission(Permission.REPO_ADMIN, "Retrieving repository hook").call(new Operation<Boolean, Exception>()
			{
				@Override
				public Boolean perform() throws Exception 
				{
					RepositoryHook repositoryHook = repositoryHookService.getByKey(repository, Constants.PLUGIN_KEY); 
					return repositoryHook != null && repositoryHook.isEnabled() && repositoryHookService.getSettings(repository, Constants.PLUGIN_KEY) != null;
				}
			}).booleanValue();
		}
		catch (Exception e)
		{
			logger.error("Failed: IsPluginEnabled({})", repository.getName(), e);
			return false;
		}
	}

	private Settings GetSettings(final Repository repository)
	{
		permissionValidationService.validateForRepository(repository, Permission.REPO_READ);
		try 
		{
			return securityService.withPermission(Permission.REPO_ADMIN, "Retrieving settings").call(new Operation<Settings, Exception>()
			{
				@Override
				public Settings perform() throws Exception { return repositoryHookService.getSettings(repository, Constants.PLUGIN_KEY); } 
			});
		}
		catch(Exception e)
		{
			logger.error("Failed: GetSettings({})", repository.getName(), e);
			return null;
		}
	}
  
	public boolean CheckFromRefChanged(final Repository repository)
	{
		Settings settings = GetSettings(repository);
		return settings != null && settings.getBoolean(Constants.CONFIG_KEY_CHECKFROMREFCHANGED, false);
	}

	public void PostChange(final Repository repository, String ref, String sha, String toRef, String pullRequestNbr)
	{
		if(!IsPluginEnabled(repository)) { return; }
		
		Settings settings = GetSettings(repository);
		String url = null;
		if(pullRequestNbr == null)
		{
			String regex = settings.getString(Constants.CONFIG_KEY_REFREGEX);
			logger.error("{} {}", regex, ref);
			if(regex != null && !regex.trim().isEmpty() && !ref.matches(regex)){ return; }
			url = GetUrl(repository, ref, sha);
		}
		else {url = GetPullRequestUrl(repository, ref, sha, toRef, pullRequestNbr, false);}
		
		boolean useSecureSsl = settings != null && settings.getBoolean(Constants.CONFIG_KEY_REQUIRESSLCERTS, false);
		Post(url, useSecureSsl);
	}

	public String GetUrl(final Repository repository, String ref, String sha)
	{
		String baseUrl = GetSettings(repository).getString(Constants.CONFIG_KEY_URL);
		StringBuilder urlParams = new StringBuilder();
		try
		{
			urlParams.append("STASH_REF=" + URLEncoder.encode(ref, "UTF-8"));
			urlParams.append("&STASH_SHA=" + URLEncoder.encode(sha, "UTF-8"));
			urlParams.append("&STASH_REPO=" + URLEncoder.encode(repository.getName(), "UTF-8"));
			urlParams.append("&STASH_PROJECT=" + URLEncoder.encode(repository.getProject().getKey(), "UTF-8"));
		}
		catch (UnsupportedEncodingException e)
		{
			logger.error("Failed to get URL ({}, {}, {})", new Object[]{repository.getName(), ref, sha});
			throw new RuntimeException(e);
		}

		return CombineURL(baseUrl, urlParams.toString());
	}

	public String GetPullRequestUrl(final Repository repository, String ref, String sha, String toRef, String pullRequestNbr, boolean fromWebUI)
	{
		String baseUrl = GetSettings(repository).getString(Constants.CONFIG_KEY_PRURL);
		StringBuilder urlParams = new StringBuilder();
		try
		{
			urlParams.append("STASH_REF=" + URLEncoder.encode(ref, "UTF-8"));
			urlParams.append("&STASH_SHA=" + URLEncoder.encode(sha, "UTF-8"));
			urlParams.append("&STASH_TO_REF=" + URLEncoder.encode(toRef, "UTF-8"));
			urlParams.append("&STASH_REPO=" + URLEncoder.encode(repository.getName(), "UTF-8"));
			urlParams.append("&STASH_PROJECT=" + URLEncoder.encode(repository.getProject().getKey(), "UTF-8"));
			urlParams.append("&STASH_PULL_REQUEST=" + pullRequestNbr);
			if(fromWebUI) {urlParams.append("&STASH_TRIGGER=" + URLEncoder.encode("build_button", "UTF-8"));}
		}
		catch (UnsupportedEncodingException e)
		{
			logger.error("Failed to get URL ({}, {}, {}, {})", new Object[]{repository.getName(), ref, sha, pullRequestNbr});
			throw new RuntimeException(e);
		}

		return CombineURL(baseUrl, urlParams.toString());
	}
	
	private String CombineURL(String baseUrl, String urlParams)
	{
		//If the URL already includes query parameters then append them
		int index = baseUrl.indexOf("?");
		return baseUrl.concat( (index == -1 ? "?" : "&") + urlParams.toString());
	}
	
	public void Post(String url, boolean useSecureSsl) 
	{
		try 
		{
			logger.info("Begin Posting to URL: {}", url);
			int index = url.indexOf("?");
			String baseUrl = url.substring(0, index);
			String urlParams = url.substring(index + 1);
			HttpsURLConnection conn = (HttpsURLConnection)(new URL(baseUrl).openConnection());
			
			SSLContext sc = SSLContext.getInstance("TLS");
			sc.init(null, new TrustManager[] { useSecureSsl ? new SecureX509TrustManager() : new UnsecureX509TrustManager() }, new SecureRandom());
			conn.setSSLSocketFactory(sc.getSocketFactory());
			conn.setHostnameVerifier(new HostnameVerifier() { public boolean verify(String string, SSLSession ssls) { return true; }});
			conn.setDoOutput(true);  // Triggers POST
			conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

			DataOutputStream wr = new DataOutputStream(conn.getOutputStream ());
			wr.writeBytes(urlParams);
			wr.flush();
			wr.close();

			conn.getInputStream().close();
			logger.info("Success Posting to URL: {}", url);
		} 
		catch (Exception e)  { logger.error("Failed Posting to URL: {}", url, e); }
	}

	public boolean ArePullRequestsConfigured(Repository repository) 
	{
		String pullRequestUrl = GetSettings(repository).getString(Constants.CONFIG_KEY_PRURL);
		return IsPluginEnabled(repository) && pullRequestUrl != null && !pullRequestUrl.isEmpty(); 
	}
}
