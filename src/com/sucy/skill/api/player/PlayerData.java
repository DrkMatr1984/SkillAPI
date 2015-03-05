package com.sucy.skill.api.player;

import com.rit.sucy.config.FilterType;
import com.rit.sucy.items.InventoryManager;
import com.rit.sucy.player.Protection;
import com.rit.sucy.player.TargetHelper;
import com.rit.sucy.version.VersionManager;
import com.rit.sucy.version.VersionPlayer;
import com.sucy.skill.SkillAPI;
import com.sucy.skill.api.classes.RPGClass;
import com.sucy.skill.api.enums.*;
import com.sucy.skill.api.event.*;
import com.sucy.skill.api.skills.PassiveSkill;
import com.sucy.skill.api.skills.Skill;
import com.sucy.skill.api.skills.SkillShot;
import com.sucy.skill.api.skills.TargetSkill;
import com.sucy.skill.data.GroupSettings;
import com.sucy.skill.data.Permissions;
import com.sucy.skill.language.ErrorNodes;
import com.sucy.skill.language.RPGFilter;
import com.sucy.skill.listener.TreeListener;
import com.sucy.skill.manager.ClassBoardManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.security.Permission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

/**
 * Represents one account for a player which can contain one class from each group
 * and the skills in each of those classes. You should not instantiate this class
 * yourself and instead get it from the SkillAPI static methods.
 */
public final class PlayerData
{
    private final HashMap<String, PlayerClass>   classes = new HashMap<String, PlayerClass>();
    private final HashMap<String, PlayerSkill>   skills  = new HashMap<String, PlayerSkill>();
    private final HashMap<Material, PlayerSkill> binds   = new HashMap<Material, PlayerSkill>();

    private OfflinePlayer  player;
    private PlayerSkillBar skillBar;
    private double         mana;
    private double         maxMana;
    private double         bonusHealth;
    private double         bonusMana;
    private boolean        init;

    /**
     * Initializes a new account data representation for a player.
     *
     * @param player player to store the data for
     */
    public PlayerData(OfflinePlayer player, boolean init)
    {
        this.player = player;
        this.skillBar = new PlayerSkillBar(this);
        this.init = SkillAPI.isLoaded() && init;
        for (String group : SkillAPI.getGroups())
        {
            GroupSettings settings = SkillAPI.getSettings().getGroupSettings(group);
            RPGClass rpgClass = settings.getDefault();

            if (rpgClass != null && settings.getPermission() == null)
            {
                setClass(rpgClass);
            }
        }
    }

    /**
     * Retrieves the Bukkit player object of the owner
     *
     * @return Bukkit player object of the owner or null if offline
     */
    public Player getPlayer()
    {
        return player.getPlayer();
    }

    /**
     * Retrieves the name of the owner
     *
     * @return name of the owner
     */
    public String getPlayerName()
    {
        return player.getName();
    }

    /**
     * Retrieves the skill bar data for the owner
     *
     * @return skill bar data of the owner
     */
    public PlayerSkillBar getSkillBar()
    {
        return skillBar;
    }

    /**
     * Ends the initialization flag for the data. Used by the
     * API to avoid async issues. Do not use this in other
     * plugins.
     */
    public void endInit()
    {
        init = false;
    }

    ///////////////////////////////////////////////////////
    //                                                   //
    //                      Skills                       //
    //                                                   //
    ///////////////////////////////////////////////////////

    /**
     * Checks if the owner has a skill by name. This is not case-sensitive
     * and does not check to see if the skill is unlocked. It only checks if
     * the skill is available to upgrade/use.
     *
     * @param name name of the skill
     *
     * @return true if has the skill, false otherwise
     */
    public boolean hasSkill(String name)
    {
        if (name == null)
        {
            return false;
        }
        return skills.containsKey(name.toLowerCase());
    }

    /**
     * Retrieves a skill of the owner by name. This is not case-sensitive.
     *
     * @param name name of the skill
     *
     * @return data for the skill or null if the player doesn't have the skill
     */
    public PlayerSkill getSkill(String name)
    {
        if (name == null)
        {
            return null;
        }
        return skills.get(name.toLowerCase());
    }

    /**
     * Retrieves all of the skill data the player has. Modifying this
     * collection will not modify the player's owned skills but modifying
     * one of the elements will change that element's data for the player.
     *
     * @return collection of skill data for the owner
     */
    public Collection<PlayerSkill> getSkills()
    {
        return skills.values();
    }

    /**
     * Retrieves the level of a skill for the owner. This is not case-sensitive.
     *
     * @param name name of the skill
     *
     * @return level of the skill or 0 if not found
     */
    public int getSkillLevel(String name)
    {
        PlayerSkill skill = getSkill(name);
        return skill == null ? 0 : skill.getLevel();
    }

    /**
     * Gives the player a skill outside of the normal class skills.
     * This skill will not show up in a skill tree.
     *
     * @param skill skill to give the player
     */
    public void giveSkill(Skill skill)
    {
        giveSkill(skill, null);
    }

    /**
     * Gives the player a skill using the class data as a parent. This
     * skill will not show up in a skill tree.
     *
     * @param skill  skill to give the player
     * @param parent parent class data
     */
    public void giveSkill(Skill skill, PlayerClass parent)
    {
        String key = skill.getName().toLowerCase();
        if (!skills.containsKey(key))
        {
            PlayerSkill data = new PlayerSkill(this, skill, parent);
            skills.put(key, data);
            int lastLevel = 0;
            while (data.getCost() == 0 && !data.isMaxed())
            {
                upgradeSkill(skill);
                if (lastLevel == data.getLevel())
                {
                    break;
                }
                lastLevel++;
            }
        }
    }

    /**
     * Upgrades a skill owned by the player. The player must own the skill,
     * have enough skill points, meet the level and skill requirements, and
     * not have maxed out the skill already in order to upgrade the skill.
     * This will consume the skill point cost while upgrading the skill.
     *
     * @param skill skill to upgrade
     *
     * @return true if successfully was upgraded, false otherwise
     */
    public boolean upgradeSkill(Skill skill)
    {
        // Cannot be null
        if (skill == null)
        {
            return false;
        }

        // Must be a valid available skill
        PlayerSkill data = skills.get(skill.getName().toLowerCase());
        if (data == null)
        {
            return false;
        }

        // Must meet any skill requirements
        if (skill.getSkillReq() != null)
        {
            PlayerSkill req = skills.get(skill.getSkillReq().toLowerCase());
            if (req != null && req.getLevel() < skill.getSkillReqLevel())
            {
                return false;
            }
        }

        int level = data.getPlayerClass().getLevel();
        int points = data.getPlayerClass().getPoints();
        int cost = data.getCost();
        if (!data.isMaxed() && level >= data.getLevelReq() && points >= cost)
        {
            // Upgrade event
            PlayerSkillUpgradeEvent event = new PlayerSkillUpgradeEvent(this, data, cost);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled())
            {
                return false;
            }

            // Apply upgrade
            data.getPlayerClass().usePoints(cost);
            data.addLevels(1);

            // Passive calls
            Player player = getPlayer();
            if (player != null && skill instanceof PassiveSkill)
            {
                if (data.getLevel() == 1)
                {
                    ((PassiveSkill) skill).initialize(player, data.getLevel());
                }
                else
                {
                    ((PassiveSkill) skill).update(player, data.getLevel() - 1, data.getLevel());
                }
            }

            // Unlock event
            if (data.getLevel() == 1)
            {
                Bukkit.getPluginManager().callEvent(new PlayerSkillUnlockEvent(this, data));
            }

            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Downgrades a skill owned by the player. The player must own the skill and it must
     * not currently be level 0 for the player to downgrade the skill. This will refund
     * the skill point cost when downgrading the skill.
     *
     * @param skill skill to downgrade
     *
     * @return true if successfully downgraded, false otherwise
     */
    public boolean downgradeSkill(Skill skill)
    {
        // Cannot be null
        if (skill == null)
        {
            return false;
        }

        // Must be a valid available skill
        PlayerSkill data = skills.get(skill.getName().toLowerCase());
        if (data == null)
        {
            return false;
        }

        // Must not be required by another skill
        for (PlayerSkill s : skills.values())
        {
            if (s.getData().getSkillReq() != null && s.getData().getSkillReq().equalsIgnoreCase(skill.getName()) && data.getLevel() <= s.getData().getSkillReqLevel())
            {
                return false;
            }
        }

        int cost = skill.getCost(data.getLevel() - 1);
        if (data.getLevel() > 0)
        {
            // Upgrade event
            PlayerSkillDowngradeEvent event = new PlayerSkillDowngradeEvent(this, data, cost);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled())
            {
                return false;
            }

            // Apply upgrade
            data.getPlayerClass().givePoints(cost, PointSource.REFUND);
            data.addLevels(-1);

            // Passive calls
            Player player = getPlayer();
            if (player != null && skill instanceof PassiveSkill)
            {
                if (data.getLevel() == 0)
                {
                    ((PassiveSkill) skill).stopEffects(player, 1);
                }
                else
                {
                    ((PassiveSkill) skill).update(player, data.getLevel() + 1, data.getLevel());
                }
            }

            // Clear bindings
            if (data.getLevel() == 0)
            {
                clearBinds(skill);
            }

            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Shows the skill tree for the player. If the player has multiple trees,
     * this will show the list of skill trees they can view.
     */
    public void showSkills()
    {
        showSkills(getPlayer());
    }

    /**
     * Shows the skill tree for the player. If the player has multiple trees,
     * this will show the list of skill trees they can view.
     *
     * @param player player to show the skill tree for
     *
     * @return true if able to show the player, false otherwise
     */
    public boolean showSkills(Player player)
    {
        // Cannot show an invalid player, and cannot show no skills
        if (player == null || classes.size() == 0 || skills.size() == 0)
        {
            return false;
        }

        // Show skill tree of only class
        if (classes.size() == 1)
        {
            PlayerClass playerClass = classes.get(classes.keySet().toArray(new String[1])[0]);
            if (playerClass.getData().getSkills().size() == 0)
            {
                return false;
            }

            player.openInventory(playerClass.getData().getSkillTree().getInventory(this));
            return true;
        }

        // Show list of classes that have skill trees
        else
        {
            Inventory inv = InventoryManager.createInventory(TreeListener.CLASS_LIST_KEY, (classes.size() + 8) / 9, player.getName());
            for (PlayerClass c : classes.values())
            {
                inv.addItem(c.getData().getIcon());
            }
            player.openInventory(inv);
            return true;
        }
    }

    ///////////////////////////////////////////////////////
    //                                                   //
    //                     Classes                       //
    //                                                   //
    ///////////////////////////////////////////////////////

    /**
     * Checks whether or not the player has as least one class they have professed as.
     *
     * @return true if professed, false otherwise
     */
    public boolean hasClass()
    {
        return classes.size() > 0;
    }

    /**
     * Checks whether or not a player has a class within the given group
     *
     * @param group class group to check
     * @return true if has a class in the group, false otherwise
     */
    public boolean hasClass(String group) { return classes.containsKey(group); }

    /**
     * Retrieves the collection of the data for classes the player has professed as.
     *
     * @return collection of the data for professed classes
     */
    public Collection<PlayerClass> getClasses()
    {
        return classes.values();
    }

    /**
     * Retrieves the data of a class the player professed as by group. This is
     * case-sensitive.
     *
     * @param group group to get the profession for
     *
     * @return professed class data or null if not professed for the group
     */
    public PlayerClass getClass(String group)
    {
        return classes.get(group);
    }

    /**
     * Retrieves the data of the professed class under the main class group. The
     * "main" group is determined by the setting in the config.
     *
     * @return main professed class data or null if not professed for the main group
     */
    public PlayerClass getMainClass()
    {
        String main = SkillAPI.getSettings().getMainGroup();
        if (classes.containsKey(main))
        {
            return classes.get(main);
        }
        else if (classes.size() > 0)
        {
            return classes.values().toArray(new PlayerClass[classes.size()])[0];
        }
        else
        {
            return null;
        }
    }

    /**
     * Sets the professed class for the player for the corresponding group. This
     * will not save any skills, experience, or levels of the previous class if
     * there was any. The new class will start at level 1 with 0 experience.
     *
     * @param rpgClass class to assign to the player
     *
     * @return the player-specific data for the new class
     */
    public PlayerClass setClass(RPGClass rpgClass)
    {

        PlayerClass c = classes.remove(rpgClass.getGroup());
        if (c != null)
        {
            for (Skill skill : c.getData().getSkills())
            {
                skills.remove(skill.getName().toLowerCase());
            }
        }

        classes.put(rpgClass.getGroup(), new PlayerClass(this, rpgClass));
        updateLevelBar();
        updateHealthAndMana(getPlayer());
        updateScoreboard();
        return classes.get(rpgClass.getGroup());
    }

    /**
     * Checks whether or not the player is professed as the class
     * without checking child classes.
     *
     * @param rpgClass class to check
     *
     * @return true if professed as the specific class, false otherwise
     */
    public boolean isExactClass(RPGClass rpgClass)
    {
        return rpgClass != null && classes.get(rpgClass.getGroup()).getData() == rpgClass;
    }

    /**
     * Checks whether or not the player is professed as the class
     * or any of its children.
     *
     * @param rpgClass class to check
     *
     * @return true if professed as the class or one of its children, false otherwise
     */
    public boolean isClass(RPGClass rpgClass)
    {
        if (rpgClass == null || classes.get(rpgClass.getGroup()) == null)
        {
            return false;
        }

        RPGClass temp = classes.get(rpgClass.getGroup()).getData();
        while (temp != null)
        {
            if (temp == rpgClass)
            {
                return true;
            }
            temp = temp.getParent();
        }

        return false;
    }

    /**
     * Checks whether or not the player can profess into the given class. This
     * checks to make sure the player is currently professed as the parent of the
     * given class and is high enough of a level to do so.
     *
     * @param rpgClass class to check
     *
     * @return true if can profess, false otherwise
     */
    public boolean canProfess(RPGClass rpgClass)
    {
        if (rpgClass.isNeedsPermission())
        {
            Player p = getPlayer();
            if (p == null || (!p.hasPermission(Permissions.CLASS) && !p.hasPermission(Permissions.CLASS + "." + rpgClass.getName().toLowerCase().replace(" ", "-"))))
            {
                return false;
            }
        }
        if (classes.containsKey(rpgClass.getGroup()))
        {
            PlayerClass current = classes.get(rpgClass.getGroup());
            return rpgClass.getParent() == current.getData() && current.getData().getMaxLevel() <= current.getLevel();
        }
        else
        {
            return !rpgClass.hasParent();
        }
    }

    /**
     * Resets the class data for the owner under the given group. This will remove
     * the profession entirely, leaving no remaining data until the player professes
     * again to a starting class.
     *
     * @param group group to reset
     */
    public void reset(String group)
    {
        PlayerClass playerClass = classes.remove(group);
        if (playerClass != null)
        {
            RPGClass data = playerClass.getData();
            for (Skill skill : data.getSkills())
            {
                skills.remove(skill.getName());
            }

            Bukkit.getPluginManager().callEvent(new PlayerClassChangeEvent(playerClass, data, null));
            updateLevelBar();
            if (getPlayer() != null)
            {
                ClassBoardManager.clear(new VersionPlayer(getPlayer()));
            }
        }
        GroupSettings settings = SkillAPI.getSettings().getGroupSettings(group);
        RPGClass rpgClass = settings.getDefault();

        if (rpgClass != null && settings.getPermission() == null)
        {
            setClass(rpgClass);
        }
    }

    /**
     * Resets all profession data for the player. This clears all professions the player
     * has, leaving no remaining data until the player professes again to a starting class.
     */
    public void resetAll()
    {
        ArrayList<String> keys = new ArrayList<String>(classes.keySet());
        for (String key : keys)
        {
            reset(key);
        }
    }

    /**
     * Professes the player into the class if they are able to. This will
     * reset the class data if the group options are set to reset upon
     * profession. Otherwise, all skills, experience, and levels of the
     * current class under the group will be retained and carried over into
     * the new profession.
     *
     * @param rpgClass class to profess into
     *
     * @return true if successfully professed, false otherwise
     */
    public boolean profess(RPGClass rpgClass)
    {
        if (rpgClass != null && canProfess(rpgClass))
        {
            // Reset data if applicable
            if (SkillAPI.getSettings().getGroupSettings(rpgClass.getGroup()).isProfessReset())
            {
                reset(rpgClass.getGroup());
            }

            // Inherit previous class data if any
            PlayerClass current = classes.get(rpgClass.getGroup());
            RPGClass previous;
            if (current == null)
            {
                previous = null;
                current = new PlayerClass(this, rpgClass);
                classes.put(rpgClass.getGroup(), current);
            }
            else
            {
                previous = current.getData();
                current.setClassData(rpgClass);
            }

            // Add skills
            for (Skill skill : rpgClass.getSkills())
            {
                if (!skills.containsKey(skill.getKey()))
                {
                    skills.put(skill.getKey(), new PlayerSkill(this, skill, current));
                }
            }

            Bukkit.getPluginManager().callEvent(new PlayerClassChangeEvent(current, previous, current.getData()));
            updateHealthAndMana(getPlayer());
            updateScoreboard();
            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Gives experience to the player from the given source
     *
     * @param amount amount of experience to give
     * @param source source of the experience
     */
    public void giveExp(double amount, ExpSource source)
    {
        for (PlayerClass playerClass : classes.values())
        {
            playerClass.giveExp(amount, source);
        }
        updateLevelBar();
    }

    /**
     * Causes the player to lose experience as a penalty (generally for dying)
     */
    public void loseExp()
    {
        for (PlayerClass playerClass : classes.values())
        {
            playerClass.loseExp(playerClass.getData().getGroupSettings().getDeathPenalty());
        }
        updateLevelBar();
    }

    /**
     * Gives levels to the player for all classes matching the experience source
     *
     * @param amount amount of levels to give
     * @param source source of the levels
     */
    public void giveLevels(int amount, ExpSource source)
    {
        for (PlayerClass playerClass : classes.values())
        {
            RPGClass data = playerClass.getData();
            if (data.receivesExp(source))
            {
                int exp = 0;
                int count = 0;
                int temp = amount;
                while (temp > 0)
                {
                    temp--;
                    exp += data.getRequiredExp(playerClass.getLevel() + count++);
                }
                playerClass.giveExp(exp, source);
            }
        }
        updateLevelBar();
        updateHealthAndMana(getPlayer());
    }

    /**
     * Gives skill points to the player for all classes matching the experience source
     *
     * @param amount amount of levels to give
     * @param source source of the levels
     */
    public void givePoints(int amount, ExpSource source)
    {
        for (PlayerClass playerClass : classes.values())
        {
            if (playerClass.getData().receivesExp(source))
            {
                playerClass.givePoints(amount);
            }
        }
    }

    ///////////////////////////////////////////////////////
    //                                                   //
    //                  Health and Mana                  //
    //                                                   //
    ///////////////////////////////////////////////////////

    /**
     * Updates the player's max health and mana using class data.
     *
     * @param player player to update the health and mana for
     */
    public void updateHealthAndMana(Player player)
    {
        if (player == null)
        {
            return;
        }

        // Update maxes
        double health = bonusHealth;
        maxMana = bonusMana;
        for (PlayerClass c : classes.values())
        {
            health += c.getHealth();
            maxMana += c.getMana();
        }
        if (health == bonusHealth)
        {
            health += SkillAPI.getSettings().getDefaultHealth();
        }
        if (health == 0)
        {
            health = 20;
        }
        VersionManager.setMaxHealth(player, health);
        mana = Math.min(mana, maxMana);

        // Health scaling is available starting with 1.6.2
        if (VersionManager.isVersionAtLeast(VersionManager.V1_6_2))
        {
            if (SkillAPI.getSettings().isOldHealth())
            {
                player.setHealthScaled(true);
                player.setHealthScale(20);
            }
            else
            {
                player.setHealthScaled(false);
            }
        }
    }

    /**
     * Gives max health to the player. This does not carry over to other accounts
     * and will reset when SkillAPI is disabled. This does however carry over through
     * death and professions. This will accept negative values.
     *
     * @param amount amount of bonus health to give
     */
    public void addMaxHealth(double amount)
    {
        bonusHealth += amount;
        Player player = getPlayer();
        if (player != null)
        {
            VersionManager.setMaxHealth(player, player.getMaxHealth() + amount);
            VersionManager.heal(player, amount);
        }
    }

    /**
     * Gives max mana to the player. This does not carry over to other accounts
     * and will reset when SkillAPI is disabled. This does however carry over through
     * death and professions. This will accept negative values.
     *
     * @param amount amount of bonus mana to give
     */
    public void addMaxMana(double amount)
    {
        bonusMana += amount;
        maxMana += amount;
        mana += amount;
    }

    /**
     * Retrieves the amount of mana the player currently has.
     *
     * @return current player mana
     */
    public double getMana()
    {
        return mana;
    }

    /**
     * Checks whether or not the player has at least the specified amount of mana
     *
     * @param amount required mana amount
     *
     * @return true if has the amount of mana, false otherwise
     */
    public boolean hasMana(double amount)
    {
        return mana >= amount;
    }

    /**
     * Retrieves the max amount of mana the player can have including bonus mana
     *
     * @return max amount of mana the player can have
     */
    public double getMaxMana()
    {
        return maxMana;
    }

    /**
     * Regenerates mana for the player based on the regen amounts of professed classes
     */
    public void regenMana()
    {
        double amount = 0;
        for (PlayerClass c : classes.values())
        {
            if (c.getData().hasManaRegen())
            {
                amount += c.getData().getManaRegen();
            }
        }
        if (amount > 0)
        {
            giveMana(amount, ManaSource.REGEN);
        }
    }

    /**
     * Gives mana to the player from an unknown source. This will not
     * cause the player's mana to go above their max amount.
     *
     * @param amount amount of mana to give
     */
    public void giveMana(double amount)
    {
        giveMana(amount, ManaSource.SPECIAL);
    }

    /**
     * Gives mana to the player from the given mana source. This will not
     * cause the player's mana to go above the max amount.
     *
     * @param amount amount of mana to give
     * @param source source of the mana
     */
    public void giveMana(double amount, ManaSource source)
    {
        PlayerManaGainEvent event = new PlayerManaGainEvent(this, amount, source);
        Bukkit.getPluginManager().callEvent(event);

        if (!event.isCancelled())
        {
            mana += event.getAmount();
            if (mana > maxMana)
            {
                mana = maxMana;
            }
        }
    }

    /**
     * Takes mana away from the player for an unknown reason. This will not
     * cause the player to fall below 0 mana.
     *
     * @param amount amount of mana to take away
     */
    public void useMana(double amount)
    {
        useMana(amount, ManaCost.SPECIAL);
    }

    /**
     * Takes mana away from the player for the specified reason. This will not
     * cause the player to fall below 0 mana.
     *
     * @param amount amount of mana to take away
     * @param cost   source of the mana cost
     */
    public void useMana(double amount, ManaCost cost)
    {
        PlayerManaLossEvent event = new PlayerManaLossEvent(this, amount, cost);
        Bukkit.getPluginManager().callEvent(event);

        if (!event.isCancelled())
        {
            mana -= event.getAmount();
            if (mana < 0)
            {
                mana = 0;
            }
        }
    }

    ///////////////////////////////////////////////////////
    //                                                   //
    //                   Skill Binding                   //
    //                                                   //
    ///////////////////////////////////////////////////////

    /**
     * Retrieves a skill the player has bound by material
     *
     * @param mat material to get the bind for
     *
     * @return skill bound to the material or null if none are bound
     */
    public PlayerSkill getBoundSkill(Material mat)
    {
        return binds.get(mat);
    }

    /**
     * Retrieves the bound data for the player. Modifying this map will
     * modify the bindings the player has.
     *
     * @return the skill binds data for the player
     */
    public HashMap<Material, PlayerSkill> getBinds()
    {
        return binds;
    }

    /**
     * Checks whether or not the material has a skill bound to it
     *
     * @param mat material to check
     *
     * @return true if a skill is bound to it, false otherwise
     */
    public boolean isBound(Material mat)
    {
        return binds.containsKey(mat);
    }

    /**
     * Binds a skill to a material for the player. The bind will not work if the skill
     * was already bound to the material.
     *
     * @param mat   material to bind the skill to
     * @param skill skill to bind to the material
     *
     * @return true if was able to bind the skill, false otherwise
     */
    public boolean bind(Material mat, PlayerSkill skill)
    {
        // Make sure the skill is owned by the player
        if (skill != null && skill.getPlayerData() != this)
        {
            throw new IllegalArgumentException("That skill does not belong to this player!");
        }

        PlayerSkill bound = getBoundSkill(mat);
        if (bound != skill)
        {
            // Apply the binding
            if (skill == null)
            {
                binds.remove(mat);
            }
            else
            {
                binds.put(mat, skill);
            }

            // Update the old skill's bind
            if (bound != null)
            {
                bound.setBind(null);
            }

            // Update the new skill's bind
            if (skill != null)
            {
                skill.setBind(mat);
            }

            return true;
        }

        // The skill was already bound
        else
        {
            return false;
        }
    }

    /**
     * Clears a skill binding on the material. If there is no binding on the
     * material, this will do nothing.
     *
     * @param mat material to clear bindings from
     *
     * @return true if a binding was cleared, false otherwise
     */
    public boolean clearBind(Material mat)
    {
        return binds.remove(mat) != null;
    }

    /**
     * Clears the skill binding for the given skill. This will remove the bindings
     * on all materials involving the skill.
     *
     * @param skill skill to unbind
     */
    public void clearBinds(Skill skill)
    {
        for (Material key : binds.keySet())
        {
            PlayerSkill bound = binds.get(key);
            if (bound.getData() == skill)
            {
                binds.remove(key);
            }
        }
    }

    /**
     * Clears all binds the player currently has
     */
    public void clearAllBinds()
    {
        binds.clear();
    }

    ///////////////////////////////////////////////////////
    //                                                   //
    //                     Functions                     //
    //                                                   //
    ///////////////////////////////////////////////////////

    /**
     * Updates the scoreboard with the player's current class.
     * This is already done by the API and doesn't need to be
     * done by other plugins.
     */
    public void updateScoreboard()
    {
        PlayerClass main = getMainClass();
        if (main != null && !init)
        {
            ClassBoardManager.update(this, main.getData().getPrefix(), main.getData().getPrefixColor());
        }
    }

    /**
     * Updates the level bar for the player if they're online and
     * the setting is enabled. The level bar will be set to the
     * level and experience progress of the main group's profession.
     * If the main group doesn't have a profession, this will fall back
     * to the first profession found or set the level to 0 if not
     * professed as any class.
     */
    public void updateLevelBar()
    {
        Player player = getPlayer();
        if (player != null && SkillAPI.getSettings().isUseLevelBar())
        {
            if (hasClass())
            {
                PlayerClass c = getMainClass();
                player.setLevel(c.getLevel());
                player.setExp((float) (c.getExp() / c.getRequiredExp()));
            }
            else
            {
                player.setLevel(0);
                player.setExp(0);
            }
        }
    }

    /**
     * Starts passive abilities for the player if they are online. This is
     * already called by the API and shouldn't be called by other plugins.
     *
     * @param player player to set the passive skills up for
     */
    public void startPassives(Player player)
    {
        if (player == null)
        {
            return;
        }
        for (PlayerSkill skill : skills.values())
        {
            if (skill.isUnlocked() && (skill.getData() instanceof PassiveSkill))
            {
                ((PassiveSkill) skill.getData()).initialize(player, skill.getLevel());
            }
        }
    }

    /**
     * Stops passive abilities for the player if they are online. This is already
     * called by the API and shouldn't be called by other plugins.
     *
     * @param player player to stop the passive skills for
     */
    public void stopPassives(Player player)
    {
        if (player == null)
        {
            return;
        }
        for (PlayerSkill skill : skills.values())
        {
            if (skill.isUnlocked() && (skill.getData() instanceof PassiveSkill))
            {
                ((PassiveSkill) skill.getData()).stopEffects(player, skill.getLevel());
            }
        }
    }

    /**
     * Casts a skill by name for the player. In order to cast the skill,
     * the player must be online, have the skill unlocked, have enough mana,
     * have the skill off cooldown, and have a proper target if applicable.
     *
     * @param skillName name of the skill ot cast
     *
     * @return true if successfully cast the skill, false otherwise
     */
    public boolean cast(String skillName)
    {
        return cast(skills.get(skillName.toLowerCase()));
    }

    /**
     * Casts a skill for the player. In order to cast the skill,
     * the player must be online, have the skill unlocked, have enough mana,
     * have the skill off cooldown, and have a proper target if applicable.
     *
     * @param skill skill to cast
     *
     * @return true if successfully cast the skill, false otherwise
     */
    public boolean cast(PlayerSkill skill)
    {
        // Invalid skill
        if (skill == null)
        {
            throw new IllegalArgumentException("Skill cannot be null");
        }

        SkillStatus status = skill.getStatus();
        int level = skill.getLevel();
        double cost = skill.getData().getManaCost(level);

        // Not unlocked
        if (level <= 0)
        {
            return false;
        }

        // On Cooldown
        if (status == SkillStatus.ON_COOLDOWN)
        {
            SkillAPI.getLanguage().sendMessage(
                    ErrorNodes.COOLDOWN,
                    getPlayer(),
                    FilterType.COLOR,
                    RPGFilter.COOLDOWN.setReplacement(skill.getCooldown() + ""),
                    RPGFilter.SKILL.setReplacement(skill.getData().getName())
            );
        }

        // Not enough mana
        else if (status == SkillStatus.MISSING_MANA)
        {
            SkillAPI.getLanguage().sendMessage(
                    ErrorNodes.MANA,
                    getPlayer(),
                    FilterType.COLOR,
                    RPGFilter.SKILL.setReplacement(skill.getData().getName()),
                    RPGFilter.MANA.setReplacement(getMana() + ""),
                    RPGFilter.COST.setReplacement((int) Math.ceil(cost) + ""),
                    RPGFilter.MISSING.setReplacement((int) Math.ceil(cost - getMana()) + "")
            );
        }

        // Skill Shots
        else if (skill.getData() instanceof SkillShot)
        {
            Player p = getPlayer();
            PlayerCastSkillEvent event = new PlayerCastSkillEvent(this, skill, p);
            Bukkit.getPluginManager().callEvent(event);

            // Make sure it isn't cancelled
            if (!event.isCancelled())
            {
                try
                {
                    if (((SkillShot) skill.getData()).cast(p, level))
                    {
                        skill.startCooldown();
                        if (SkillAPI.getSettings().isShowSkillMessages())
                        {
                            skill.getData().sendMessage(p, SkillAPI.getSettings().getMessageRadius());
                        }
                        if (SkillAPI.getSettings().isManaEnabled())
                        {
                            useMana(cost, ManaCost.SKILL_CAST);
                        }
                        return true;
                    }
                }
                catch (Exception ex)
                {
                    Bukkit.getLogger().severe("Failed to cast skill - " + skill.getData().getName() + ": Internal skill error");
                    ex.printStackTrace();
                }
            }
        }

        // Target Skills
        else if (skill.getData() instanceof TargetSkill)
        {

            Player p = getPlayer();
            LivingEntity target = TargetHelper.getLivingTarget(p, skill.getData().getRange(level));

            // Must have a target
            if (target == null)
            {
                return false;
            }

            PlayerCastSkillEvent event = new PlayerCastSkillEvent(this, skill, p);
            Bukkit.getPluginManager().callEvent(event);

            // Make sure it isn't cancelled
            if (!event.isCancelled())
            {
                try
                {
                    if (((TargetSkill) skill.getData()).cast(p, target, level, Protection.isAlly(p, target)))
                    {
                        skill.startCooldown();
                        if (SkillAPI.getSettings().isShowSkillMessages())
                        {
                            skill.getData().sendMessage(p, SkillAPI.getSettings().getMessageRadius());
                        }
                        if (SkillAPI.getSettings().isManaEnabled())
                        {
                            useMana(cost, ManaCost.SKILL_CAST);
                        }
                        return true;
                    }
                }
                catch (Exception ex)
                {
                    Bukkit.getLogger().severe("Failed to cast skill - " + skill.getData().getName() + ": Internal skill error");
                    ex.printStackTrace();
                }
            }
        }

        return false;
    }
}
