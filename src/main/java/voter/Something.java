package voter;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.io.CharStreams;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import static com.google.common.collect.Maps.newHashMap;

public class Something
{
  public static final String HTTP_2013_CONFITURA_PL_C4P_VOTING = "http://2013.confitura.pl/c4p/voting/";
  private final String START_URL = "http://2013.confitura.pl/view/1/v4p";

  private final Map<String, String> persistentParams = newHashMap();
  private final Configuration c = new Configuration();

  private final DefaultHttpClient httpClient;
  private InputCollector inputCollector;

  Stack<HttpUriRequest> requestsToPerform = new Stack<>();

  public static void main(String[] args) throws IOException
  {
    new Something().doSomething();
  }

  private void doSomething() throws IOException
  {
    initializeFlow();
    executeFlow();
  }

  public Something() throws IOException
  {
    inputCollector = new InputCollector();
    httpClient = new DefaultHttpClient();
  }

  private void initializeFlow()
  {
    HttpGet get = new HttpGet(START_URL);
    requestsToPerform.push(get);
  }

  void executeFlow() throws IOException
  {
    while (true)
    {
      if (requestsToPerform.isEmpty())
      {
        return;
      }

      HttpUriRequest request = requestsToPerform.pop();
      HttpResponse response = httpClient.execute(request);

      Map<String, String> headers = logHeaders(response);

      String html = readHtml(response);
      Document document = Jsoup.parse(html);
      Elements metas = document.select("meta");
      if (foundRedirects(metas, headers))
      {
        continue;
      }

      Elements form = document.getElementsByTag("form");
      if (form.isEmpty())
      {
        continue;
      }

      Vote vote = figureOutContent(document.getElementById("page"));
      simulateVoting(form, vote);
    }
  }

  private Vote figureOutContent(Element page) throws IOException
  {
    String author = page.getElementsByTag("h3").first().text();
    String title = page.getElementsByTag("h2").first().text();
    String text = page.getElementsByAttributeValue("class", "accordion").text();

    return decide(author, title, text);
  }

  private Vote decide(String author, String title, String text) throws IOException
  {
    if (shouldNotBeAccepted(author))
    {
      System.out.println(String.format("Short circuiting %s out", author));
      return Vote.NO_WAY;
    }

    if (shouldBeAccepted(author))
    {
      System.out.println(String.format("Yes, we want %s", author));
      return Vote.YES;
    }

    System.out.println("--------------------------------");
    System.out.println(author);
    System.out.println(title);
    System.out.println(Joiner.on("\n").join(Splitter.fixedLength(80).split(text)));
    System.out.println("--------------------------------");

    Vote vote = inputCollector.collect();
    System.out.println(String.format("author %s got %s", author, vote));
    return vote;
  }

  private boolean shouldBeAccepted(String author)
  {
    String[] likeList = new String[]{
        "Sobótka", "Lawrey", "Margiel"
    };
    for (String name : likeList)
    {
      if (author.contains(name))
      {
        return true;
      }
    }
    return false;
  }

  private boolean shouldNotBeAccepted(String author)
  {
    String[] hateList = new String[]{
        // you wish
    };

    for (String name : hateList)
    {
      if (author.contains(name))
      {
        return true;
      }
    }
    return false;
  }

  private void simulateVoting(Elements form, Vote vote) throws UnsupportedEncodingException
  {
    List<NameValuePair> nvps = new ArrayList<>();

    for (Element input : form.select("input"))
    {
      if ("hidden".equals(input.attr("type")))
      {
        nvps.add(new BasicNameValuePair(input.attr("id"), ""));
        break;
      }
    }

    Map<Vote, NameValuePair> votes = optionsForVotes(form);
    nvps.add(votes.get(vote));

    String action = form.attr("action");
    String url = HTTP_2013_CONFITURA_PL_C4P_VOTING + action;
    HttpPost post = new HttpPost(url);
    post.setEntity(new UrlEncodedFormEntity(nvps));
    requestsToPerform.push(post);
  }

  private Map<Vote, NameValuePair> optionsForVotes(Elements form)
  {
    Map<Vote, NameValuePair> votes = newHashMap();
    for (Element label : form.select("label"))
    {
      String text = label.text().toLowerCase();
      if (text.contains("nie interesuje mnie"))
      {
        Element input = label.select("input").first();
        votes.put(Vote.NO_WAY, new BasicNameValuePair(input.attr("name"), input.val()));
      }
      if (text.contains("jeszcze nie wiem"))
      {
        Element input = label.select("input").first();
        votes.put(Vote.MEH, new BasicNameValuePair(input.attr("name"), input.val()));
      }
      if (text.contains("chcę zobaczyć"))
      {
        Element input = label.select("input").first();
        votes.put(Vote.YES, new BasicNameValuePair(input.attr("name"), input.val()));
      }
    }
    return votes;
  }

  private boolean foundRedirects(Elements metas, Map<String, String> headers) throws IOException
  {
    String newUrl = lookForRedirects(metas, headers);
    if (newUrl.equals(START_URL))
    {
      return false;
    }

    HttpGet httpGet = new HttpGet(newUrl);
    addPersistentHeaders(httpGet);
    requestsToPerform.push(httpGet);
    return true;
  }

  private void addPersistentHeaders(HttpGet httpGet)
  {
    for (Map.Entry<String, String> entry : persistentParams.entrySet())
    {
      httpGet.setHeader(entry.getKey(), entry.getValue());
    }
  }

  private String readHtml(HttpResponse response) throws IOException
  {
    HttpEntity entity = response.getEntity();
    InputStream content = entity.getContent();
    List<String> strings = CharStreams.readLines(new InputStreamReader(content));
    return Joiner.on("\n").join(strings);
  }

  private String lookForRedirects(Elements metas, Map<String, String> headers)
  {
    for (Map.Entry<String, String> header : headers.entrySet())
    {
      if ("Location".equalsIgnoreCase(header.getKey()))
      {
        String url = header.getValue();
        System.out.println("Redirecting to: " + url);
        return url;
      }
    }
    for (Element meta : metas)
    {
      if ("REFRESH".equals(meta.attr("http-equiv")))
      {
        String content = meta.attr("content");
        String url = content.substring(content.indexOf("url=") + 4);
        System.out.println("Redirecting to: " + url);
        return url;
      }
    }
    return START_URL;
  }

  private Map<String, String> logHeaders(HttpResponse response)
  {
    Map<String, String> headers = newHashMap();
    Header[] allHeaders = response.getAllHeaders();
    for (Header header : allHeaders)
    {
      String name = header.getName();
      String value = header.getValue();
      if (name.equals("Set-Cookie"))
      {
        persistentParams.put(name, value.substring(value.indexOf(";")));
      }
      if (c.shouldPrintHeaders())
      {
        System.out.println(name + " " + value);
      }
      headers.put(name, value);
    }

    return headers;
  }
}

class Configuration
{
  boolean shouldPrintHeaders()
  {
    return false;
  }
}

enum Vote
{
  YES, MEH, NO_WAY;
}

class InputCollector
{
  private final BufferedReader reader;

  InputCollector()
  {
    reader = new BufferedReader(new InputStreamReader(System.in));
  }

  public Vote collect() throws IOException
  {
    System.out.println("Z\tX\tC");
    System.out.println("-1\t0\t+1");

    while (true)
    {
      String line = reader.readLine();

      if (line.trim().equalsIgnoreCase("C"))
      {
        return Vote.YES;
      }
      if (line.trim().equalsIgnoreCase("X"))
      {
        return Vote.MEH;
      }
      if (line.trim().equalsIgnoreCase("Z"))
      {
        return Vote.NO_WAY;
      }
    }
  }
}