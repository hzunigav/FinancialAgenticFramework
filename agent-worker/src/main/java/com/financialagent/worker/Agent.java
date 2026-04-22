package com.financialagent.worker;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Properties;

public class Agent {

    public static void main(String[] args) throws IOException {
        Properties config = loadConfig();
        String portalUrl = config.getProperty("portal.url");
        String username = config.getProperty("portal.username");
        String password = config.getProperty("portal.password");

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();

            page.navigate(portalUrl + "/login");
            page.fill("input[name='username']", username);
            page.fill("input[name='password']", password);
            page.click("button[type='submit']");

            page.waitForURL("**/report");
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get("target/report.png"))
                    .setFullPage(true));

            System.out.println("Landed on: " + page.url());
            System.out.println("Page title: " + page.title());
            System.out.println("Screenshot saved to target/report.png");

            browser.close();
        }
    }

    private static Properties loadConfig() throws IOException {
        Properties props = new Properties();
        try (InputStream in = Agent.class.getResourceAsStream("/config.properties")) {
            if (in == null) {
                throw new IllegalStateException("config.properties not found on classpath");
            }
            props.load(in);
        }
        return props;
    }
}
