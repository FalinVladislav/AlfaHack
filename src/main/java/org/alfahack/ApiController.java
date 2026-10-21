package org.alfahack;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private final AuthService auth;private final Products products;private final Drafts drafts;private final Applications apps;private final Documents docs;private final Forecast forecast;private final Assistant assistant;private final BusinessInsights insights;private final SyntheticProfiles profiles;
    public ApiController(AuthService auth,Products products,Drafts drafts,Applications apps,Documents docs,Forecast forecast,Assistant assistant,BusinessInsights insights,SyntheticProfiles profiles){this.auth=auth;this.products=products;this.drafts=drafts;this.apps=apps;this.docs=docs;this.forecast=forecast;this.assistant=assistant;this.insights=insights;this.profiles=profiles;}
    private AuthService.User user(HttpServletRequest request){return auth.user(request);}
    @PostMapping("/auth/sessions") public Map<String,Object> login(@RequestBody Map<String,Object> body,HttpServletResponse response){return auth.login(Util.required(body,"login"),Util.required(body,"password"),response);}
    @PostMapping("/logout") public Map<String,Object> logout(HttpServletRequest request,HttpServletResponse response){auth.logout(user(request),request,response);return Map.of("ok",true);}
    @GetMapping("/me") public Map<String,Object> me(HttpServletRequest request){return auth.me(user(request));}
    @GetMapping("/products") public List<Map<String,Object>> products(HttpServletRequest request){user(request);return products.list();}
    @GetMapping("/dashboard") public Map<String,Object> dashboard(HttpServletRequest request){return forecast.dashboard(user(request));}
    @GetMapping("/insights") public Map<String,Object> insights(HttpServletRequest request){return insights.insights(user(request));}
    @GetMapping("/cashflow/summary") public Map<String,Object> cashflow(HttpServletRequest request){return insights.cashflow(user(request));}
    @GetMapping("/synthetic/profiles") public List<Map<String,Object>> syntheticProfiles(HttpServletRequest request){AuthService.User u=user(request);u.staff();return profiles.catalog();}
    @GetMapping("/cashflow/forecast") public Map<String,Object> forecast(HttpServletRequest request,@RequestParam(defaultValue="30D")String horizon,@RequestParam(defaultValue="BASE")String scenario){return forecast.forecast(user(request),horizon,scenario);}
    @GetMapping("/drafts") public List<Map<String,Object>> drafts(HttpServletRequest request){return drafts.list(user(request));}
    @PostMapping("/drafts") public Map<String,Object> createDraft(HttpServletRequest request){return drafts.create(user(request));}
    @GetMapping("/drafts/{id}") public Map<String,Object> draft(HttpServletRequest request,@PathVariable String id){return drafts.get(user(request),id);}
    @PatchMapping("/drafts/{id}") public Map<String,Object> updateDraft(HttpServletRequest request,@PathVariable String id,@RequestBody Map<String,Object> body){return drafts.update(user(request),id,body);}
    @PostMapping("/drafts/{id}/quotes") public Map<String,Object> quote(HttpServletRequest request,@PathVariable String id,@RequestBody Map<String,Object> body){return drafts.quote(user(request),id,body);}
    @PostMapping("/quotes/{id}/analysis") public Map<String,Object> analysis(HttpServletRequest request,@PathVariable String id,@RequestBody Map<String,Object> body){AuthService.User u=user(request);return forecast.analysis(u,drafts.quoteById(u,id),Util.string(body,"horizon")==null?"30D":Util.string(body,"horizon"),Util.string(body,"scenario")==null?"BASE":Util.string(body,"scenario"));}
    @GetMapping("/applications") public List<Map<String,Object>> applications(HttpServletRequest request){return apps.list(user(request));}
    @PostMapping("/applications") public Map<String,Object> submit(HttpServletRequest request,@RequestBody Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false)String key){return apps.submit(user(request),body,key);}
    @GetMapping("/applications/{id}") public Map<String,Object> application(HttpServletRequest request,@PathVariable String id){return apps.detail(user(request),id);}
    @PostMapping("/applications/{id}/checks") public Map<String,Object> check(HttpServletRequest request,@PathVariable String id,@RequestBody(required=false)Map<String,Object> body){return apps.check(user(request),id,body==null?null:Util.string(body,"demo_profile"));}
    @PostMapping("/applications/{id}/documents") public Map<String,Object> upload(HttpServletRequest request,@PathVariable String id,@RequestParam String request_id,@RequestParam MultipartFile file) throws IOException{return docs.upload(user(request),id,request_id,file);}
    @PostMapping("/applications/{id}/requests/{requestId}/complete") public Map<String,Object> complete(HttpServletRequest request,@PathVariable String id,@PathVariable String requestId){return docs.complete(user(request),id,requestId);}
    @GetMapping("/applications/{id}/documents/{documentId}") public ResponseEntity<byte[]> download(HttpServletRequest request,@PathVariable String id,@PathVariable String documentId) throws IOException {
        byte[] bytes=docs.download(user(request),id,documentId);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=verified-document").contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes);
    }
    @PostMapping("/applications/{id}/offer/accept") public Map<String,Object> accept(HttpServletRequest request,@PathVariable String id,@RequestBody Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false)String key){return apps.accept(user(request),id,body,key);}
    @PostMapping("/applications/{id}/offer/decline") public Map<String,Object> decline(HttpServletRequest request,@PathVariable String id){return apps.decline(user(request),id);}
    @GetMapping("/applications/{id}/contract") public Map<String,Object> contract(HttpServletRequest request,@PathVariable String id){return apps.contract(user(request),id);}
    @PostMapping("/applications/{id}/contract/sign") public Map<String,Object> sign(HttpServletRequest request,@PathVariable String id,@RequestBody Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false)String key){return apps.sign(user(request),id,body,key);}
    @PostMapping("/applications/{id}/disburse") public Map<String,Object> disburse(HttpServletRequest request,@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key){return apps.disburse(user(request),id,key);}
    @GetMapping("/loans") public List<Map<String,Object>> loans(HttpServletRequest request){return apps.loans(user(request));}
    @GetMapping("/loans/{id}") public Map<String,Object> loan(HttpServletRequest request,@PathVariable String id){return apps.loan(user(request),id);}
    @GetMapping("/staff/applications") public List<Map<String,Object>> staffApplications(HttpServletRequest request){AuthService.User u=user(request);u.staff();return apps.list(u);}
    @GetMapping("/staff/applications/{id}") public Map<String,Object> staffApplication(HttpServletRequest request,@PathVariable String id){AuthService.User u=user(request);u.staff();return apps.detail(u,id);}
    @PostMapping("/assistant/messages") public Map<String,Object> chat(HttpServletRequest request,@RequestBody Map<String,Object> body){return assistant.respond(user(request),body);}
    @PostMapping("/assistant/proposals/{id}/apply") public Map<String,Object> applyProposal(HttpServletRequest request,@PathVariable String id,@RequestBody Map<String,Object> body){return assistant.apply(user(request),id,body);}
}
