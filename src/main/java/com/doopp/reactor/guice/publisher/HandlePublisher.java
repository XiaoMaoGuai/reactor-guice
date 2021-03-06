package com.doopp.reactor.guice.publisher;

import com.doopp.reactor.guice.StatusMessageResponse;
import com.doopp.reactor.guice.RequestAttribute;
import com.doopp.reactor.guice.annotation.RequestAttributeParam;
import com.doopp.reactor.guice.annotation.UploadFilesParam;
import com.doopp.reactor.guice.json.HttpMessageConverter;
import com.doopp.reactor.guice.view.ModelMap;
import com.doopp.reactor.guice.view.TemplateDelegate;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.multipart.*;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

public class HandlePublisher {

    private HttpMessageConverter httpMessageConverter;

    private TemplateDelegate templateDelegate;

    public void setHttpMessageConverter(HttpMessageConverter httpMessageConverter) {
        this.httpMessageConverter = httpMessageConverter;
    }

    public HttpMessageConverter getHttpMessageConverter() {
        return this.httpMessageConverter;
    }

    public void setTemplateDelegate(TemplateDelegate templateDelegate) {
        this.templateDelegate = templateDelegate;
    }

    public Mono<Object> sendResult(HttpServerRequest req, HttpServerResponse resp, Method method, Object handleObject, Object requestAttribute) {
        // ModelMap
        ModelMap modelMap = new ModelMap();
        // http content type
        String contentType = methodProductsValue(method);
        // result
        return this.invokeMethod(
                    req, resp, method, handleObject, (RequestAttribute) requestAttribute, modelMap
                )
                .map(result->{
                    resp.header(HttpHeaderNames.CONTENT_TYPE, contentType);
                    if (result instanceof String && ((String) result).startsWith("redirect:")) {
                        String uri = ((String) result).substring(9);
                        return resp.sendRedirect(uri);
                    }

                    // binary
                    if (result instanceof ByteBuf) {
                        return result;
                    }
                    // String
                    else if (result instanceof String && (contentType.contains(MediaType.TEXT_HTML) || contentType.contains(MediaType.TEXT_PLAIN))) {
                        if (this.templateDelegate==null) {
                            return result;
                        }
                        return this.templateDelegate.template(handleObject, modelMap, (String) result);
                    }
                    // json
                    else {
                        StatusMessageResponse statusMessageResponse = new StatusMessageResponse(result);
                        return (this.httpMessageConverter == null)
                                    ? statusMessageResponse.toString()
                                    : this.httpMessageConverter.toJson(statusMessageResponse);

                    }
                });
//                .onErrorMap(throwable -> {
//                    // return error
//                    if (contentType.contains(MediaType.TEXT_HTML) || contentType.contains(MediaType.TEXT_PLAIN)) {
//                        // resp.header(HttpHeaderNames.CONTENT_TYPE, contentType);
//                        return new Exception(throwable.getMessage());
//                    }
//                    else {
//                        // resp.header(HttpHeaderNames.CONTENT_TYPE, MediaType.APPLICATION_JSON);
//                        if (throwable instanceof StatusMessageException) {
//                            return throwable;
//                        }
//                        else {
//                            return new StatusMessageException(HttpResponseStatus.INTERNAL_SERVER_ERROR, throwable.getMessage());
//                        }
//                    }
//                });
    }

    private Mono<Object> invokeMethod(HttpServerRequest req, HttpServerResponse resp, Method method, Object handleObject, RequestAttribute requestAttribute, ModelMap modelMap) {
        if (req.method() == HttpMethod.POST || req.method() == HttpMethod.PUT) {
//            AtomicReference<Channel> channel = new AtomicReference<>();
            return req.withConnection(con->{
//                         channel.set(con.channel());
                    })
                    .receive()
                    .aggregate()
                    .flatMap(byteBuf -> {
//                        EventLoop eventLoop = channel.get().eventLoop();
//                        Collection<Callable<Mono<Object>>> c = Collections.EMPTY_LIST;
//                        c.add(() -> (Mono<Object>) method.invoke(handleObject, methodParams(method, req, resp, requestAttribute, modelMap, byteBuf)));
//                        List<Future<Mono<Object>>> a = eventLoop.invokeAll(c);
//                        return a.get(0);
                        try {
                            return (Mono<Object>) method.invoke(handleObject, methodParams(method, req, resp, requestAttribute, modelMap, byteBuf));
                        } catch (Exception e) {
                            return Mono.error(e);
                        }
                    });
        } else {
            try {
                return (Mono<Object>) method.invoke(handleObject, methodParams(method, req, resp, requestAttribute, modelMap, null));
            } catch (Exception e) {
                return Mono.error(e);
            }
        }
    }

    public String methodProductsValue(Method method) {
        String contentType = MediaType.TEXT_HTML;
        if (method!=null &&  method.isAnnotationPresent(Produces.class)) {
            String _contentType = "";
            for (String mediaType : method.getAnnotation(Produces.class).value()) {
                _contentType += (_contentType.equals("")) ? mediaType : "; " + mediaType;
            }
            contentType = _contentType.contains("charset") ? _contentType : _contentType + "; charset=UTF-8";
        }
        return contentType;
    }

    private Object[] methodParams(Method method, HttpServerRequest request, HttpServerResponse response, RequestAttribute requestAttribute, ModelMap modelMap, ByteBuf content) {
        ArrayList<Object> objectList = new ArrayList<>();

        Map<String, List<String>> questParams = new HashMap<>();
        Map<String, List<String>> formParams = new HashMap<>();
        Map<String, List<MemoryFileUpload>> fileParams = new HashMap<>();

        this.queryParams(request, questParams);
        this.formParams(request, content, formParams, fileParams);

        for (Parameter parameter : method.getParameters()) {
            Class<?> parameterClazz = parameter.getType();
            ArrayList<String> annotationVal = new ArrayList<>();
            // RequestAttribute
            if (parameterClazz == RequestAttribute.class) {
                objectList.add(requestAttribute);
            }
            // request
            else if (parameterClazz == HttpServerRequest.class) {
                objectList.add(request);
            }
            // response
            else if (parameterClazz == HttpServerResponse.class) {
                objectList.add(response);
            }
            // modelMap
            else if (parameterClazz == ModelMap.class) {
                objectList.add(modelMap);
            }
            // upload file
            else if (parameter.getAnnotation(UploadFilesParam.class) != null) {
                String annotationKey = parameter.getAnnotation(UploadFilesParam.class).value();
                objectList.add(fileParams.get(annotationKey));
            }
            // RequestAttribute item
            else if (parameter.getAnnotation(RequestAttributeParam.class) != null) {
                String annotationKey = parameter.getAnnotation(RequestAttributeParam.class).value();
                objectList.add(requestAttribute.getAttribute(annotationKey, parameterClazz));
            }
            // CookieParam
            else if (parameter.getAnnotation(CookieParam.class) != null) {
                String annotationKey = parameter.getAnnotation(CookieParam.class).value();
                // Collections.addAll(annotationVal, request.cookies().get(annotationKey).toString());
                objectList.add(request.cookies().get(annotationKey));
            }
            // HeaderParam
            else if (parameter.getAnnotation(HeaderParam.class) != null) {
                String annotationKey = parameter.getAnnotation(HeaderParam.class).value();
                // Collections.addAll(annotationVal, request.requestHeaders().get(annotationKey));
                objectList.add(request.requestHeaders().get(annotationKey));
            }
            // PathParam
            else if (parameter.getAnnotation(PathParam.class) != null) {
                String annotationKey = parameter.getAnnotation(PathParam.class).value();
                Collections.addAll(annotationVal, request.param(annotationKey));
                objectList.add(paramTypeValue(annotationVal, parameterClazz));
            }
            // QueryParam
            else if (parameter.getAnnotation(QueryParam.class) != null) {
                String annotationKey = parameter.getAnnotation(QueryParam.class).value();
                objectList.add(paramTypeValue(questParams.get(annotationKey), parameterClazz));
            }
            // FormParam
            else if (parameter.getAnnotation(FormParam.class) != null) {
                String annotationKey = parameter.getAnnotation(FormParam.class).value();
                objectList.add(paramTypeValue(formParams.get(annotationKey), parameterClazz));
            }
            // BeanParam
            else if (parameter.getAnnotation(BeanParam.class) != null) {
                byte[] byteArray = new byte[content.capacity()];
                content.readBytes(byteArray);
                // Type type = TypeToken.get(parameter.getAnnotatedType().getType()).getType();
                // objectList.add((new Gson()).fromJson(new String(byteArray), parameter.getAnnotatedType().getType()));
                objectList.add(httpMessageConverter.fromJson(new String(byteArray), parameterClazz));
            }
            // default
            else {
                objectList.add(parameterClazz.cast(null));
            }
        }
        return objectList.toArray();
    }

    private <T> T paramTypeValue(List<String> value, Class<T> clazz) {
        // if value is null
        if (value == null) {
            return clazz.cast(null);
        }
        // Long
        else if (clazz == Long.class) {
            return clazz.cast(Long.valueOf(value.get(0)));
        }
        // Integer
        else if (clazz == Integer.class) {
            return clazz.cast(Integer.valueOf(value.get(0)));
        }
        // Boolean
        else if (clazz == Boolean.class) {
            return clazz.cast(Boolean.valueOf(value.get(0)));
        }
        // String
        else if (clazz == String.class) {
            return clazz.cast(value.get(0));
        }
        // Float
        else if (clazz == Float.class) {
            return clazz.cast(Float.valueOf(value.get(0)));
        }
        // Double
        else if (clazz == Double.class) {
            return clazz.cast(Double.valueOf(value.get(0)));
        }
        // Short
        else if (clazz == Short.class) {
            return clazz.cast(Short.valueOf(value.get(0)));
        }
        // Long[]
        else if (clazz == Long[].class) {
            ArrayList<Long> longValues = new ArrayList<>();
            for (String s : value) {
                longValues.add(Long.valueOf(s));
            }
            return clazz.cast(longValues.toArray(new Long[0]));
        }
        // Integer[]
        else if (clazz == Integer[].class) {
            ArrayList<Integer> intValues = new ArrayList<>();
            for (String s : value) {
                intValues.add(Integer.valueOf(s));
            }
            return clazz.cast(intValues.toArray(new Integer[0]));
        }
        // String[]
        else if (clazz == String[].class) {
            return clazz.cast(value.toArray(new String[0]));
        }
        // default return null;
        return clazz.cast(value);
    }

    // Get 请求
    private void queryParams(HttpServerRequest request, Map<String, List<String>> questParams) {
        // Map<String, String> requestParams = new HashMap<>();
        // Query Params
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        Map<String, List<String>> params = decoder.parameters();
        for (Map.Entry<String, List<String>> next : params.entrySet()) {
            questParams.put(next.getKey(), next.getValue());
        }
    }

    // Post 请求
    private void formParams(HttpServerRequest request, ByteBuf content, Map<String, List<String>> formParams, Map<String, List<MemoryFileUpload>> fileParams) {
        if (content != null) {
            // POST Params
            FullHttpRequest dhr = new DefaultFullHttpRequest(request.version(), request.method(), request.uri(), content, request.requestHeaders(), EmptyHttpHeaders.INSTANCE);
            HttpPostRequestDecoder postDecoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), dhr);
            List<InterfaceHttpData> postData = postDecoder.getBodyHttpDatas();
            for (InterfaceHttpData data : postData) {
                // 一般 post 内容
                if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
                    MemoryAttribute attribute = (MemoryAttribute) data;
                    // System.out.println(data);
                    // formParams.put(attribute.getName(), attribute.getValue());
                    List<String> formParam = formParams.get(attribute.getName());
                    if (formParam==null) {
                        formParam = new ArrayList<>();
                        formParam.add(attribute.getValue());
                        formParams.put(attribute.getName(), formParam);
                    }
                    else {
                        formParam.add(attribute.getValue());
                    }
                }
                // 上传文件的内容
                else if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
                    MemoryFileUpload fileUpload = (MemoryFileUpload) data;
                    // fileParams.put(fileUpload.getName(), fileUpload);
                    // fileParams.put(fileUpload.getName(), null);
                    List<MemoryFileUpload> fileParam = fileParams.get(fileUpload.getName());
                    if (fileParam==null) {
                        fileParam = new ArrayList<>();
                        fileParam.add(fileUpload);
                        fileParams.put(fileUpload.getName(), fileParam);
                    }
                    else {
                        fileParam.add(fileUpload);
                    }
                }
            }
        }
    }
}
