package com.flemmli97.flan.claim;

import com.flemmli97.flan.config.ConfigHandler;
import com.flemmli97.flan.player.PlayerClaimData;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Claim {

    private boolean dirty;
    private int minX, minZ, maxX, maxZ, minY;

    private UUID owner;

    private UUID claimID;
    private final EnumMap<EnumPermission, Boolean> globalPerm = Maps.newEnumMap(EnumPermission.class);
    private final Map<String, EnumMap<EnumPermission, Boolean>> permissions = Maps.newHashMap();

    private final Map<UUID, String> playersGroups = Maps.newHashMap();

    private final List<Claim> subClaims = Lists.newArrayList();

    private UUID parent;
    private Claim parentClaim;

    /**
     * Flag for players tracking this claim
     */
    private boolean removed;

    private final ServerWorld world;

    private Claim(ServerWorld world) {
        this.world = world;
    }

    public Claim(BlockPos pos1, BlockPos pos2, UUID creator, ServerWorld world) {
        this(pos1.getX(), pos2.getX(), pos1.getZ(), pos2.getZ(), Math.min(pos1.getY(), pos2.getY()), creator, world);
    }

    public Claim(int x1, int x2, int z1, int z2, int minY, UUID creator, ServerWorld world) {
        this.minX = Math.min(x1, x2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxZ = Math.max(z1, z2);
        this.minY = Math.max(0, minY);
        this.owner = creator;
        this.world = world;
    }

    public static Claim fromJson(JsonObject obj, UUID owner, ServerWorld world) {
        Claim claim = new Claim(world);
        claim.readJson(obj, owner);
        return claim;
    }

    public void setClaimID(UUID uuid) {
        this.claimID = uuid;
        this.setDirty();
    }

    public UUID getClaimID() {
        return this.claimID;
    }

    public UUID getOwner() {
        return this.owner;
    }

    public Claim parentClaim(){
        if(this.parent==null)
            return null;
        if(this.parentClaim==null){
            ClaimStorage storage = ClaimStorage.get(this.world);
            this.parentClaim = storage.claimUUIDMap.get(this.parent);
        }
        return this.parentClaim;
    }

    public void copySizes(Claim claim){
        this.minX = claim.minX;
        this.maxX = claim.maxX;
        this.minZ = claim.minZ;
        this.maxZ = claim.maxZ;
        this.minY = claim.minY;
        this.removed = false;
    }

    public void setAdminClaim(){
        this.owner = null;
    }

    public int getPlane() {
        return (this.maxX - this.minX) * (this.maxZ - this.minZ);
    }

    /**
     * @return The claims dimension in order: x, X, z, Z, y
     */
    public int[] getDimensions() {
        return new int[]{this.minX, this.maxX, this.minZ, this.maxZ, this.minY};
    }

    public boolean insideClaim(BlockPos pos) {
        return this.minX <= pos.getX() && this.maxX >= pos.getX() && this.minZ <= pos.getZ() && this.maxZ >= pos.getZ() && this.minY <= pos.getY();
    }

    public boolean intersects(Claim other) {
        return this.minX < other.maxX && this.maxX > other.minX && this.minZ < other.maxZ && this.maxZ > other.minZ;
    }

    public boolean isCorner(BlockPos pos) {
        return (pos.getX() == this.minX && pos.getZ() == this.minZ) || (pos.getX() == this.minX && pos.getZ() == this.maxZ)
                || (pos.getX() == this.maxX && pos.getZ() == this.minZ) || (pos.getX() == this.maxX && pos.getZ() == this.maxZ);
    }

    public void remove() {
        this.removed = true;
    }

    public boolean isRemoved() {
        return this.removed;
    }

    public boolean canInteract(ServerPlayerEntity player, EnumPermission perm, BlockPos pos){
        return this.canInteract(player, perm, pos, false);
    }

    public boolean canInteract(ServerPlayerEntity player, EnumPermission perm, BlockPos pos, boolean message) {
        if (perm == EnumPermission.EXPLOSIONS || perm == EnumPermission.WITHER) {
            for (Claim claim : this.subClaims) {
                if (claim.insideClaim(pos)) {
                    return claim.canInteract(player, perm, pos, message);
                }
            }
            if(this.hasPerm(perm))
                return true;
            if(message)
                player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.noPermissionSimple, Formatting.DARK_RED), false);
            return false;
        }
        if (player.getUuid().equals(this.owner))
            return true;
        PlayerClaimData data = PlayerClaimData.get(player);
        if (player.hasPermissionLevel(2) && data.isAdminIgnoreClaim())
            return true;
        for (Claim claim : this.subClaims) {
            if (claim.insideClaim(pos)) {
                if(perm!=EnumPermission.EDITCLAIM && perm != EnumPermission.EDITPERMS)
                    return claim.canInteract(player, perm, pos, message);
                else if(claim.canInteract(player, perm, pos, message))
                    return true;
            }
        }
        if (this.playersGroups.containsKey(player.getUuid())) {
            EnumMap<EnumPermission, Boolean> map = this.permissions.get(this.playersGroups.get(player.getUuid()));
            if (map != null && map.containsKey(perm)) {
                if(map.get(perm))
                    return true;
                if(message)
                    player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.noPermissionSimple, Formatting.DARK_RED), false);
                return false;
            }
        }
        if(this.hasPerm(perm))
            return true;
        if(message)
            player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.noPermissionSimple, Formatting.DARK_RED), false);
        return false;
    }

    /**
     * @return -1 for default, 0 for false, 1 for true
     */
    public int permEnabled(EnumPermission perm) {
        return !this.globalPerm.containsKey(perm)?-1:this.globalPerm.get(perm)?1:0;
    }

    private boolean hasPerm(EnumPermission perm){
        if(this.parentClaim()==null)
            return this.permEnabled(perm) == 1;
        if(this.permEnabled(perm)==-1)
            return this.parentClaim().permEnabled(perm)==1;
        return this.permEnabled(perm) == 1;
    }

    private UUID generateUUID() {
        UUID uuid = UUID.randomUUID();
        for (Claim claim : this.subClaims)
            if(claim.claimID.equals(uuid)) {
                return generateUUID();
            }
        return uuid;
    }

    public Set<Claim> tryCreateSubClaim(BlockPos pos1, BlockPos pos2) {
        Claim sub = new Claim(pos1, new BlockPos(pos2.getX(), 0, pos2.getZ()), this.owner, this.world);
        sub.setClaimID(this.generateUUID());
        Set<Claim> conflicts = Sets.newHashSet();
        for(Claim other : this.subClaims)
            if (sub.intersects(other)) {
                conflicts.add(sub);
            }
        if(conflicts.isEmpty()) {
            sub.parent = this.claimID;
            sub.parentClaim = this;
            this.subClaims.add(sub);
        }
        return conflicts;
    }

    public void addSubClaimGriefprevention(Claim claim){
        claim.setClaimID(this.generateUUID());
        claim.parent = this.claimID;
        claim.parentClaim = this;
        this.subClaims.add(claim);
    }

    public Claim getSubClaim(BlockPos pos) {
        for (Claim claim : this.subClaims)
            if (claim.insideClaim(pos))
                return claim;
        return null;
    }

    public boolean deleteSubClaim(Claim claim){
        return this.subClaims.remove(claim);
    }

    public List<Claim> getAllSubclaims(){
        return ImmutableList.copyOf(this.subClaims);
    }

    public Set<Claim> resizeSubclaim(Claim claim, BlockPos from, BlockPos to){
        int[] dims = claim.getDimensions();
        BlockPos opposite = new BlockPos(dims[0]==from.getX()?dims[1]:dims[0], dims[4], dims[2]==from.getZ()?dims[3]:dims[2]);
        Claim newClaim = new Claim(opposite, to, claim.claimID, this.world);
        Set<Claim> conflicts = Sets.newHashSet();
        for(Claim other : this.subClaims)
            if (!claim.equals(other) && newClaim.intersects(other))
                conflicts.add(other);
        if(conflicts.isEmpty())
            claim.copySizes(newClaim);
        return conflicts;
    }

    public boolean setPlayerGroup(UUID player, String group, boolean force) {
        if(this.owner!=null && this.owner.equals(player))
            return false;
        if (group == null) {
            this.playersGroups.remove(player);
            this.setDirty();
            return true;
        }

        if (!this.playersGroups.containsKey(player) || force) {
            this.playersGroups.put(player, group);
            this.setDirty();
            return true;
        }
        return false;
    }

    public List<String> playersFromGroup(MinecraftServer server, String group) {
        List<UUID> l = Lists.newArrayList();
        this.playersGroups.forEach((uuid, g) -> {
            if (g.equals(group))
                l.add(uuid);
        });
        List<String> names = Lists.newArrayList();
        l.forEach(uuid -> {
            GameProfile prof = server.getUserCache().getByUuid(uuid);
            if (prof != null)
                names.add(prof.getName());
        });
        names.sort(null);
        return names;
    }

    public void editGlobalPerms(EnumPermission toggle, int mode) {
        if (mode > 1)
            mode = -1;
        if (mode == -1)
            this.globalPerm.remove(toggle);
        else
            this.globalPerm.put(toggle, mode == 1);
        this.setDirty();
    }

    /**
     * Edit the permissions for a group. If not defined for the group creates a new default permission map for that group
     *
     * @param mode -1 = makes it resort to the global perm, 0 = deny perm, 1 = allow perm
     * @return If editing was successful or not
     */
    public boolean editPerms(ServerPlayerEntity player, String group, EnumPermission perm, int mode) {
        if (this.canInteract(player, EnumPermission.EDITPERMS, player.getBlockPos())) {
            if (mode > 1)
                mode = -1;
            boolean has = this.permissions.containsKey(group);
            EnumMap<EnumPermission, Boolean> perms = has ? this.permissions.get(group) : new EnumMap<>(EnumPermission.class);
            if (mode == -1)
                perms.remove(perm);
            else
                perms.put(perm, mode == 1);
            if (!has)
                this.permissions.put(group, perms);
            this.setDirty();
            return true;
        }
        return false;
    }

    public boolean removePermGroup(ServerPlayerEntity player, String group) {
        if (this.canInteract(player, EnumPermission.EDITPERMS, player.getBlockPos())) {
            this.permissions.remove(group);
            List<UUID> toRemove = Lists.newArrayList();
            this.playersGroups.forEach((uuid, g) -> {
                if (g.equals(group))
                    toRemove.add(uuid);
            });
            toRemove.forEach(this.playersGroups::remove);
            this.setDirty();
            return true;
        }
        return false;
    }

    public int groupHasPerm(String rank, EnumPermission perm) {
        if (!this.permissions.containsKey(rank) || !this.permissions.get(rank).containsKey(perm))
            return -1;
        return this.permissions.get(rank).get(perm) ? 1 : 0;
    }

    public List<String> groups() {
        List<String> l = Lists.newArrayList(this.permissions.keySet());
        l.sort(null);
        return l;
    }

    public void setDirty() {
        this.dirty = true;
    }

    public boolean isDirty() {
        return this.dirty;
    }

    public void readJson(JsonObject obj, UUID uuid) {
        JsonArray pos = obj.getAsJsonArray("PosxXzZY");
        this.minX = pos.get(0).getAsInt();
        this.maxX = pos.get(1).getAsInt();
        this.minZ = pos.get(2).getAsInt();
        this.maxZ = pos.get(3).getAsInt();
        this.minY = pos.get(4).getAsInt();
        this.owner = uuid;
        this.claimID = UUID.fromString(obj.get("ID").getAsString());
        this.globalPerm.clear();
        this.permissions.clear();
        this.subClaims.clear();
        if(obj.has("Parent"))
            this.parent = UUID.fromString(obj.get("Parent").getAsString());
        if (obj.has("GlobalPerms")) {
            if(this.parent==null) {
                obj.getAsJsonArray("GlobalPerms").forEach(perm -> {
                    this.globalPerm.put(EnumPermission.valueOf(perm.getAsString()), true);
                });
            }
            else{
                obj.getAsJsonObject("GlobalPerms").entrySet().forEach(entry->{
                    this.globalPerm.put(EnumPermission.valueOf(entry.getKey()), entry.getValue().getAsBoolean());
                });
            }
        }
        if (obj.has("PermGroup")) {
            JsonObject perms = obj.getAsJsonObject("PermGroup");
            perms.entrySet().forEach(key -> {
                EnumMap<EnumPermission, Boolean> map = new EnumMap<>(EnumPermission.class);
                JsonObject group = key.getValue().getAsJsonObject();
                group.entrySet().forEach(gkey -> map.put(EnumPermission.valueOf(gkey.getKey()), gkey.getValue().getAsBoolean()));
                this.permissions.put(key.getKey(), map);
            });
        }
        if (obj.has("PlayerPerms")) {
            JsonObject pl = obj.getAsJsonObject("PlayerPerms");
            pl.entrySet().forEach(key -> this.playersGroups.put(UUID.fromString(key.getKey()), key.getValue().getAsString()));
        }
        if (obj.has("SubClaims")) {
            obj.getAsJsonArray("SubClaims").forEach(sub -> this.subClaims.add(Claim.fromJson(sub.getAsJsonObject(), this.owner, this.world)));
        }
    }

    public JsonObject toJson(JsonObject obj) {
        JsonArray pos = new JsonArray();
        pos.add(this.minX);
        pos.add(this.maxX);
        pos.add(this.minZ);
        pos.add(this.maxZ);
        pos.add(this.minY);
        obj.add("PosxXzZY", pos);
        obj.addProperty("ID", this.claimID.toString());
        if(this.parent!=null)
            obj.addProperty("Parent", this.parent.toString());
        if (!this.globalPerm.isEmpty()) {
            JsonElement gPerm;
            if(this.parent==null) {
                gPerm = new JsonArray();
                this.globalPerm.forEach((perm, bool) -> {
                    if (bool)
                        ((JsonArray) gPerm).add(perm.toString());
                });
            }
            else{
                gPerm = new JsonObject();
                this.globalPerm.forEach((perm, bool) -> {
                    ((JsonObject) gPerm).addProperty(perm.toString(), bool);
                });
            }
            obj.add("GlobalPerms", gPerm);
        }
        if (!this.permissions.isEmpty()) {
            JsonObject perms = new JsonObject();
            this.permissions.forEach((s, pmap) -> {
                JsonObject group = new JsonObject();
                pmap.forEach((perm, bool) -> group.addProperty(perm.toString(), bool));
                perms.add(s, group);
            });
            obj.add("PermGroup", perms);
        }
        if (!this.playersGroups.isEmpty()) {
            JsonObject pl = new JsonObject();
            this.playersGroups.forEach((uuid, s) -> pl.addProperty(uuid.toString(), s));
            obj.add("PlayerPerms", pl);
        }
        if (!this.subClaims.isEmpty()) {
            JsonArray list = new JsonArray();
            this.subClaims.forEach(p -> list.add(p.toJson(new JsonObject())));
            obj.add("SubClaims", list);
        }
        return obj;
    }

    @Override
    public int hashCode() {
        return this.claimID==null?Arrays.hashCode(this.getDimensions()):this.claimID.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj instanceof Claim) {
            Claim other = (Claim) obj;
            if (this.claimID==null && other.claimID==null)
                return Arrays.equals(this.getDimensions(), ((Claim) obj).getDimensions());
            if(this.claimID!=null)
                return this.claimID.equals(((Claim) obj).claimID);
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format("Claim:[ID=%s, Owner=%s, from: x=%d; z=%d, to: x=%d, z=%d", this.claimID!=null?this.claimID.toString():"null", this.owner.toString(), this.minX, this.minZ, this.maxX, this.maxZ);
    }

    public String formattedClaim() {
        return String.format("[x=%d,z=%d] - [x=%d,z=%d]", this.minX, this.minZ, this.maxX, this.maxZ);
    }

    public List<Text> infoString(ServerPlayerEntity player){
        boolean perms = this.canInteract(player, EnumPermission.EDITPERMS, player.getBlockPos());
        List<Text> l = Lists.newArrayList();
        l.add(PermHelper.simpleColoredText("=============================================", Formatting.GREEN));
        GameProfile prof = this.owner!=null?player.getServer().getUserCache().getByUuid(this.owner):null;
        String ownerName = this.owner==null?"Admin":prof!=null?prof.getName():"<UNKNOWN>";
        l.add(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.claimBasicInfo, ownerName, this.minX, this.minZ, this.maxX, this.maxZ, this.subClaims.size()), Formatting.GOLD));
        if(perms) {
            l.add(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.claimInfoPerms, this.globalPerm), Formatting.RED));
            l.add(PermHelper.simpleColoredText(ConfigHandler.lang.claimGroupInfoHeader, Formatting.RED));
            Map<String, List<String>> nameToGroup = Maps.newHashMap();
            for (Map.Entry<UUID, String> e : this.playersGroups.entrySet()) {
                GameProfile pgroup = player.getServer().getUserCache().getByUuid(e.getKey());
                if (prof != null) {
                    nameToGroup.merge(e.getValue(), Lists.newArrayList(pgroup.getName()), (old, val) -> {
                        old.add(pgroup.getName());
                        return old;
                    });
                }
            }
            for (Map.Entry<String, EnumMap<EnumPermission, Boolean>> e : this.permissions.entrySet()) {
                l.add(PermHelper.simpleColoredText(String.format("  %s:", e.getKey()), Formatting.DARK_RED));
                l.add(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.claimGroupPerms, e.getValue()), Formatting.RED));
                l.add(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.claimGroupPlayers, nameToGroup.getOrDefault(e.getKey(), Lists.newArrayList())), Formatting.RED));
            }
        }
        l.add(PermHelper.simpleColoredText("=============================================", Formatting.GREEN));
        return l;
    }
}