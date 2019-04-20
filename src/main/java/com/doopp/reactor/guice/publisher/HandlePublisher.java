package com.doopp.reactor.guice.publisher;


import com.doopp.reactor.guice.annotation.RequestAttributeParam;
import com.doopp.reactor.guice.annotation.UploadFilesParam;
import com.doopp.reactor.guice.common.*;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.multipart.*;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

public class HandlePublisher {

    private HttpMessageConverter httpMessageConverter;

    private TemplateDelegate templateDelegate;

    public void setHttpMessageConverter(HttpMessageConverter httpMessageConverter) {
        this.httpMessageConverter = httpMessageConverter;
    }

    public void setTemplateDelegate(TemplateDelegate templateDelegate) {
        this.templateDelegate = templateDelegate;
    }

    public Mono<Object> sendResult(HttpServerRequest req, HttpServerResponse resp, Method method, Object handleObject, Object requestAttribute) {

        ModelMap modelMap = new ModelMap();
        return Mono.just(new ModelMap())
                .flatMap(m ->
                    this.invokeMethod(req, resp, method, handleObject, (RequestAttribute) requestAttribute, modelMap)
                )
                .flatMap(o -> {
                    // content type
                    String contentType = MediaType.TEXT_HTML;
                    if (method.isAnnotationPresent(Produces.class)) {
                        String _contentType = "";
                        for (String mediaType : method.getAnnotation(Produces.class).value()) {
                            _contentType += (_contentType.equals("")) ? mediaType : "; " + mediaType;
                        }
                        contentType = _contentType.contains("charset") ? _contentType : _contentType + "; charset=UTF-8";
                    }

                    resp.addHeader(HttpHeaderNames.CONTENT_TYPE, contentType);

                    // binary
                    if (o instanceof ByteBuf) {
                        return ByteBufMono.just((ByteBuf) o).map(s->{
                            // resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(s.readableBytes()));
                            return s;
                        });
                    }
                    // String
                    else if (o instanceof String &&
                            (contentType.contains(MediaType.TEXT_HTML) || contentType.contains(MediaType.TEXT_PLAIN))) {
                        return (this.templateDelegate==null)
                            ? Mono.just((String) o)
                            : this.templateDelegate.templateMono(handleObject, modelMap, (String) o);
                    }
                    // json
                    else {
                        return Mono.just(o).map(JsonResponse::new).flatMap(oo->
                            (this.httpMessageConverter==null)
                                ? Mono.just(o.toString())
                                : this.httpMessageConverter.toJson(oo)
                        );
                    }
                });
    }

    private Mono<Object> invokeMethod(HttpServerRequest req, HttpServerResponse resp, Method method, Object handleObject, RequestAttribute requestAttribute, ModelMap modelMap) {
        if (req.method() == HttpMethod.POST) {
            return req
                    .receive()
                    .aggregate()
                    .flatMap(byteBuf -> {
                        try {
                            return (Mono<Object>) method.invoke(handleObject, getMethodParams(method, req, resp, requestAttribute, modelMap, byteBuf));
                        } catch (Exception e) {
                            return Mono.error(e);
                        }
                    });
        } else {
            try {
                return (Mono<Object>) method.invoke(handleObject, getMethodParams(method, req, resp, requestAttribute, modelMap, null));
            } catch (Exception e) {
                return Mono.error(e);
            }
        }
    }

    private Object[] getMethodParams(Method method, HttpServerRequest request, HttpServerResponse response, RequestAttribute requestAttribute, ModelMap modelMap, ByteBuf content) {
        ArrayList<Object> objectList = new ArrayList<>();

        Map<String, String> questParams = new HashMap<>();
        Map<String, String> formParams = new HashMap<>();
        Map<String, MemoryFileUpload> fileParams = new HashMap<>();

        this.queryParams(request, questParams);
        this.formParams(request, content, formParams, fileParams);

        for (Parameter parameter : method.getParameters()) {
            Class<?> parameterClazz = parameter.getType();
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
                objectList.add(getParamTypeValue(request.cookies().get(annotationKey).toString(), parameterClazz));
            }
            // HeaderParam
            else if (parameter.getAnnotation(HeaderParam.class) != null) {
                String annotationKey = parameter.getAnnotation(HeaderParam.class).value();
                objectList.add(getParamTypeValue(request.requestHeaders().get(annotationKey), parameterClazz));
            }
            // QueryParam
            else if (parameter.getAnnotation(QueryParam.class) != null) {
                String annotationKey = parameter.getAnnotation(QueryParam.class).value();
                objectList.add(getParamTypeValue(questParams.get(annotationKey), parameterClazz));
            }
            // PathParam
            else if (parameter.getAnnotation(PathParam.class) != null) {
                String annotationKey = parameter.getAnnotation(PathParam.class).value();
                objectList.add(getParamTypeValue(request.param(annotationKey), parameterClazz));
            }
            // FormParam
            else if (parameter.getAnnotation(FormParam.class) != null) {
                String annotationKey = parameter.getAnnotation(FormParam.class).value();
                objectList.add(getParamTypeValue(formParams.get(annotationKey), parameterClazz));
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

    private <T> T getParamTypeValue(String value, Class<T> clazz) {
        // Long
        if (clazz == Long.class) {
            return clazz.cast(Long.valueOf(value));
        }
        // Integer
        else if (clazz == Integer.class) {
            return clazz.cast(Integer.valueOf(value));
        }
        // Boolean
        else if (clazz == Boolean.class) {
            return clazz.cast(Boolean.valueOf(value));
        }
        // String
        else if (clazz == String.class) {
            return clazz.cast(value);
        }
        // Float
        else if (clazz == Float.class) {
            return clazz.cast(Float.valueOf(value));
        }
        // Double
        else if (clazz == Double.class) {
            return clazz.cast(Double.valueOf(value));
        }
        // Short
        else if (clazz == Short.class) {
            return clazz.cast(Short.valueOf(value));
        }
        // List<String>
        else if (clazz == new ArrayList<String>(){}.getClass()) {
            return clazz.cast(Short.valueOf(value));
        }
        // default return null;
        return null;
    }

    // Get 请求
    private void queryParams(HttpServerRequest request, Map<String, String> questParams) {
        // Map<String, String> requestParams = new HashMap<>();
        // Query Params
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        Map<String, List<String>> params = decoder.parameters();
        for (Map.Entry<String, List<String>> next : params.entrySet()) {
            questParams.put(next.getKey(), next.getValue().get(0));
        }
    }

    // Post 请求
    private void formParams(HttpServerRequest request, ByteBuf content, Map<String, String> formParams, Map<String, MemoryFileUpload> fileParams) {
        if (content != null) {
            // POST Params
            FullHttpRequest dhr = new DefaultFullHttpRequest(request.version(), request.method(), request.uri(), content, request.requestHeaders(), EmptyHttpHeaders.INSTANCE);
            HttpPostRequestDecoder postDecoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), dhr);
            List<InterfaceHttpData> postData = postDecoder.getBodyHttpDatas();
            for (InterfaceHttpData data : postData) {
                // 一般 post 内容
                if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
                    MemoryAttribute attribute = (MemoryAttribute) data;
                    formParams.put(attribute.getName(), attribute.getValue());
                }
                // 上传文件的内容
                else if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
                    MemoryFileUpload fileUpload = (MemoryFileUpload) data;
                    fileParams.put(fileUpload.getName(), fileUpload);
                }
            }
        }
    }
}