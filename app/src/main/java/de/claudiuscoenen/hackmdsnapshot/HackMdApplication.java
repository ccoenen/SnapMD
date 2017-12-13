package de.claudiuscoenen.hackmdsnapshot;

import android.app.Application;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;

import de.claudiuscoenen.hackmdsnapshot.api.HackMdApi;
import de.claudiuscoenen.hackmdsnapshot.repository.LoginDataRepository;


public class HackMdApplication extends Application {

	private HackMdApi api;
	private LoginDataRepository loginDataRepository;

	public HackMdApi getApi() {
		return api;
	}
	public LoginDataRepository getLoginDataRepository() {
		return loginDataRepository;
	}

	@Override
	public void onCreate() {
		super.onCreate();

		CookieManager cookieManager = new CookieManager();
		cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
		CookieHandler.setDefault(cookieManager);

		loginDataRepository = new LoginDataRepository(this);
		api = new HackMdApi(loginDataRepository);
	}
}
