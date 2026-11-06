package org.praxisplatform.config.service;

import java.util.Objects;
import org.praxisplatform.rules.contract.*;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Immutable product definition admitted by the host registration, independently of customer authoring. */
public record DomainRuleProductProfile(String ownerServiceKey, String hostContractVersion, RuleSetDefinition ruleSet) {
  public DomainRuleProductProfile {
    Objects.requireNonNull(ruleSet);
    if (ownerServiceKey==null || ownerServiceKey.isBlank() || hostContractVersion==null || hostContractVersion.isBlank()
        || ruleSet.bindings().stream().anyMatch(binding->binding.source()!=DecisionSource.PRODUCT))
      throw new IllegalArgumentException("A registered product profile requires owner, host contract and exclusively PRODUCT bindings");
  }
  public RuleSnapshotSource source(ObjectMapper mapper) {
    return new RuleSnapshotSource("product:"+ownerServiceKey+":"+ruleSet.ref().ruleSetKey()+":"+ruleSet.ref().operationKey(),
        ruleSet.ref().ruleSetKey(),ruleSet.ref().version(),PraxisCanonicalJson.sha256(mapper.valueToTree(this)));
  }
  /** Publication version is governed separately; every other product byte must match the registered profile. */
  public boolean matches(RuleSetDefinition candidate) {
    return ruleSet.ref().sameIdentity(candidate.ref()) && ruleSet.availableRoots().equals(candidate.availableRoots())
        && sameEntries(ruleSet.slots(),candidate.slots(),DecisionSlot::slotKey)
        && sameEntries(ruleSet.bindings(),candidate.bindings(),DecisionBinding::bindingKey)
        && ruleSet.compatibility().equals(candidate.compatibility()) && ruleSet.failPolicy()==candidate.failPolicy()
        && ruleSet.errorDescriptors().equals(candidate.errorDescriptors());
  }
  private static <T> boolean sameEntries(java.util.List<T> product,java.util.List<T> candidate,java.util.function.Function<T,String> identity) {
    if(product.size()!=candidate.size())return false;
    var expected=new java.util.HashMap<String,T>();var actual=new java.util.HashMap<String,T>();
    for(T item:product)if(expected.putIfAbsent(identity.apply(item),item)!=null)return false;
    for(T item:candidate)if(actual.putIfAbsent(identity.apply(item),item)!=null)return false;
    return expected.equals(actual);
  }
}
