# reactor-guice

Reactor-guice integrates the framework of Google Guice and Reactor-netty

#### Milestone
``` html
0.0.3 Support annotations GET POST PUT DELETE
0.0.3 Support annotations Products PATH
0.0.3 Static File Support
0.0.3 Support Websocket
0.0.4 Custom filter by uri
0.0.5 index.html in the default output directory
0.0.5 Support for custom templates lib
      with gson and Jackson convert
0.0.5 Support for custom json lib
      with freeemark convert
      and modelmap for template
0.0.5 you can upload files
0.0.6 POST can be an array

support forward & redirect (like spring)
        return redirect:/abc
        return forward:abc
support protobuf
support udp server
support api gateway model
maybe use Jersey to execute dispatch
```

#### Bugs
``` html
0.0.6 Failed to return errors accurately according to the type of request, 
      such as JSON in case of request exception of static file. 
      The error returned by the filter should be determined according 
      to the type of return that the filter subsequently executes. 
      You may need to leave response to publisher, 
      but it's not very beautiful.
```

### 1. import reactor-guice

#### maven
```
<dependency>
    <groupId>com.doopp</groupId>
    <artifactId>reactor-guice</artifactId>
    <version>0.0.6-SNAPSHOT</version>
</dependency>
```

#### gradle
```
compile 'com.doopp:reactor-guice:0.0.6-SNAPSHOT'
```

#### use Local Maven 
```
mvn clean

mvn package

mvn install:install-file -Dfile=target/reactor-guice-0.0.6.jar -DgroupId=com.doopp.local -DartifactId=reactor-guice -Dversion=0.0.6 -Dpackaging=jar

<dependency>
    <groupId>com.doopp.local</groupId>
    <artifactId>reactor-guice</artifactId>
    <version>0.0.6</version>
</dependency>
```

### 2. create you application

```java
Injector injector = Guice.createInjector(...);

ReactorGuiceServer.create()
    .bind(host, port)
    .injector(injector)
    .setHttpMessageConverter(new JacksonHttpMessageConverter())
    .setTemplateDelegate(new FreemarkTemplateDelegate())
    .handlePackages("com.doopp.reactor.guice.test.handle")
    .addFilter("/", Filter.class)
    .launch();
```

### 3. creat you service

#### Handle Example

```java
/** https://kreactor.doopp.com/test/json **/
@GET
@Path("/json")
@Produces({MediaType.APPLICATION_JSON})
public Mono<Map<String, String>> json() {
    return Mono
        .just(new HashMap<String, String>())
        .map(m -> {
            m.put("hi", "five girl");
            return m;
        });
}

/** https://kreactor.doopp.com/test/jpeg **/
@GET
@Path("/jpeg")
@Produces({"image/jpeg"})
public Mono<ByteBuf> jpeg() {
    return HttpClient.create()
        .get()
        .uri("https://static.cnbetacdn.com/article/2019/0402/6398390c491f650.jpg")
        .responseContent()
        .aggregate()
        .map(ByteBuf::retain);
}
```


#### WebSocket

```java

@Path("/kreactor/ws")
@Singleton
public class WsTestHandle extends AbstractWebSocketServerHandle {

    @Override
    public void connected(Channel channel) {
        System.out.println(channel.id());
        super.connected(channel);
    }

    @Override
    public void onTextMessage(TextWebSocketFrame frame, Channel channel) {
        System.out.println(frame.text());
        super.onTextMessage(frame, channel);
    }
}

```